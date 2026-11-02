"""Summarises bench/results/ab: per-run latency, per-variant median and range, GC activity, and which runs were excluded as not quiet."""
import json
import os
import re
import statistics as st
import sys

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", "ab")
QUIET_CPU = float(sys.argv[1]) if len(sys.argv) > 1 else 25.0   # machine CPU % before a run above which the run is excluded

cpu = {}
for line in open(os.path.join(root, "runs.txt"), encoding="utf-8"):
    m = re.match(r"variant=(\w) pair=(\d+) cpu_before=([\d.]+)", line.strip())
    if m:
        cpu[(m.group(1), int(m.group(2)))] = float(m.group(3))


def gc(prom_path):
    count = total = 0.0
    if not os.path.exists(prom_path):
        return None
    for line in open(prom_path, encoding="utf-8"):
        if line.startswith("jvm_gc_pause_seconds_count"):
            count += float(line.rsplit(" ", 1)[1])
        elif line.startswith("jvm_gc_pause_seconds_sum"):
            total += float(line.rsplit(" ", 1)[1])
    return int(count), total * 1000


rows = []
for (v, i), c in sorted(cpu.items(), key=lambda kv: (kv[0][1], kv[0][0])):
    d = os.path.join(root, f"{v}-{i}")
    p = os.path.join(d, "loadgen.json")
    if not os.path.exists(p):
        print(f"{v}-{i}: no result (run failed)")
        continue
    j = json.load(open(p, encoding="utf-8"))
    L = j["latencyMsFromIntendedSend"]
    g = gc(os.path.join(d, "prometheus.txt"))
    rows.append(dict(v=v, i=i, cpu=c, p50=L["p50"], p95=L["p95"], p99=L["p99"], p999=L["p999"], mx=L["max"], err=j["errorPct"],
                     rate=j["achievedRate"], lg=j["loadGenCpuPctOfOneCoreAvg"], gcn=g[0] if g else None, gcms=g[1] if g else None))

print(f"{'run':6} {'cpu%':>5} {'rate':>6} {'p50':>7} {'p95':>7} {'p99':>7} {'p99.9':>8} {'max':>7} {'err%':>5} {'gc#':>4} {'gc ms':>7}  note")
for r in rows:
    bad = r["cpu"] > QUIET_CPU or r["err"] > 0
    r["bad"] = bad
    print(f"{r['v']}-{r['i']:<4} {r['cpu']:5.1f} {r['rate']:6.0f} {r['p50']:7.3f} {r['p95']:7.3f} {r['p99']:7.3f} {r['p999']:8.3f} {r['mx']:7.1f} "
          f"{r['err']:5.2f} {r['gcn'] if r['gcn'] is not None else '-':>4} {r['gcms'] if r['gcms'] is not None else 0:7.1f}  {'EXCLUDED (not quiet or errors)' if bad else ''}")

print("\nper variant, quiet runs only: median [min .. max]")
out = {}
for v in "AB":
    ok = [r for r in rows if r["v"] == v and not r["bad"]]
    if not ok:
        print(f"  {v}: no quiet runs")
        continue
    out[v] = ok
    def f(key):
        xs = [r[key] for r in ok]
        return f"{st.median(xs):8.3f} [{min(xs):.3f} .. {max(xs):.3f}]"
    print(f"  {v} (n={len(ok)})  p50 {f('p50')}  p95 {f('p95')}  p99 {f('p99')}  p99.9 {f('p999')}  max {st.median([r['mx'] for r in ok]):.1f}")
    gcs = [r['gcn'] for r in ok if r['gcn'] is not None]
    if gcs:
        print(f"      GC collections per run: median {st.median(gcs):.0f}; pause total median {st.median([r['gcms'] for r in ok]):.1f} ms")
if "A" in out and "B" in out:
    print("\nB vs A (negative = B faster):")
    for key, label in (("p50", "p50"), ("p95", "p95"), ("p99", "p99"), ("p999", "p99.9")):
        a = st.median([r[key] for r in out["A"]]); b = st.median([r[key] for r in out["B"]])
        print(f"  {label:6} {b - a:+.3f} ms  ({(b / a - 1) * 100:+.1f}%)")
    # do the ranges overlap? if so the difference is not distinguishable from run-to-run noise
    for key, label in (("p99", "p99"), ("p999", "p99.9")):
        a = [r[key] for r in out["A"]]; b = [r[key] for r in out["B"]]
        print(f"  {label:6} ranges overlap: {not (max(b) < min(a) or max(a) < min(b))}")
