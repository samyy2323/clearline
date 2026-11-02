"""
Offline pCTR training. Reads the impression/click log written by the event tracker (data/events.jsonl), builds the same
hashed features the engine uses, fits a logistic regression, evaluates on a time-ordered holdout and exports sparse
weights to <out>/weights.json, which the running engine hot-reloads (polled every 5 s). Never runs on the hot path.

    python ml/train.py --events data/events.jsonl --out ml/out
"""
import argparse
import json
import os
import sys
import time

import numpy as np
from scipy.sparse import csr_matrix
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import log_loss, roc_auc_score

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hashing import BITS, row_indices  # noqa: E402


def load(path):
    imps, clicks = {}, set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue  # torn last line while the writer is still appending
            if e["t"] == "imp":
                imps[e["id"]] = e
            elif e["t"] == "click":
                clicks.add(e["id"])
    return sorted(imps.values(), key=lambda e: e["ts"]), clicks


def design(rows, clicks):
    indptr, indices, y = [0], [], []
    for e in rows:
        indices.extend(row_indices(e["camp"], e["pub"], e["cat"], e["dev"], e["geo"], e["hr"], e["segs"]))
        indptr.append(len(indices))
        y.append(1 if e["id"] in clicks else 0)
    data = np.ones(len(indices), dtype=np.float32)
    return csr_matrix((data, indices, indptr), shape=(len(rows), 1 << BITS)), np.array(y)


def calibration(p, y, bins=10):
    out = []
    for chunk in np.array_split(np.argsort(p), bins):
        out.append({"mean_pred": float(p[chunk].mean()), "observed": float(y[chunk].mean()), "n": int(len(chunk))})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--events", default="data/events.jsonl")
    ap.add_argument("--out", default="ml/out")
    ap.add_argument("--C", type=float, default=0.01, help="inverse L2 strength (picked by holdout sweep: 0.01 beats 0.03-0.5 on log loss)")
    ap.add_argument("--min-events", type=int, default=2000)
    a = ap.parse_args()

    rows, clicks = load(a.events)
    if len(rows) < a.min_events:
        sys.exit(f"only {len(rows)} impressions in {a.events}; need >= {a.min_events}. Run the simulator longer.")
    X, y = design(rows, clicks)
    split = int(len(rows) * 0.8)  # time-ordered split: never train on the future
    Xtr, ytr, Xte, yte = X[:split], y[:split], X[split:], y[split:]

    model = LogisticRegression(solver="liblinear", C=a.C, max_iter=200)
    model.fit(Xtr, ytr)
    p = model.predict_proba(Xte)[:, 1]
    base = np.full_like(p, ytr.mean())
    metrics = {
        "trained_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "impressions": len(rows), "clicks": int(y.sum()), "train": split, "test": len(rows) - split,
        "ctr_train": float(ytr.mean()), "ctr_test": float(yte.mean()), "mean_pred_test": float(p.mean()),
        "logloss": float(log_loss(yte, p)), "logloss_constant_baseline": float(log_loss(yte, base)),
        "auc": float(roc_auc_score(yte, p)) if 0 < yte.sum() < len(yte) else None,
        "calibration_deciles": calibration(p, yte),
    }

    os.makedirs(a.out, exist_ok=True)
    coef = model.coef_[0]
    nz = np.nonzero(coef)[0]
    tmp = os.path.join(a.out, "weights.json.tmp")
    with open(tmp, "w") as f:
        json.dump({"bias": float(model.intercept_[0]), "weights": {str(int(i)): round(float(coef[i]), 6) for i in nz}}, f)
    os.replace(tmp, os.path.join(a.out, "weights.json"))  # atomic: the engine never reads a half-written file
    json.dump(metrics, open(os.path.join(a.out, "metrics.json"), "w"), indent=1)
    keys = ("trained_at", "impressions", "clicks", "logloss", "logloss_constant_baseline", "auc", "mean_pred_test", "ctr_test")
    with open(os.path.join(a.out, "history.jsonl"), "a") as f:
        f.write(json.dumps({k: metrics[k] for k in keys}) + "\n")

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        c = metrics["calibration_deciles"]
        fig, ax = plt.subplots(figsize=(5.5, 5))
        ax.plot([d["mean_pred"] for d in c], [d["observed"] for d in c], "o-", label="model")
        m = max(max(d["mean_pred"], d["observed"]) for d in c)
        ax.plot([0, m], [0, m], "--", color="#888", label="perfect")
        ax.set_xlabel("mean predicted CTR")
        ax.set_ylabel("observed CTR")
        ax.set_title("Calibration (holdout deciles)")
        ax.legend()
        fig.tight_layout()
        fig.savefig(os.path.join(a.out, "calibration.png"), dpi=130)
    except ImportError:
        pass

    print(json.dumps({k: metrics[k] for k in ("impressions", "clicks", "logloss", "logloss_constant_baseline", "auc")}, indent=1))
    print(f"exported {len(nz)} non-zero weights -> {a.out}/weights.json")


if __name__ == "__main__":
    main()
