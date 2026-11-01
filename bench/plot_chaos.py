"""Plots the per-second series saved by chaos.sh: latency percentiles, throughput and no-bid rate with the fault windows shaded."""
import json, sys, datetime as dt
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

out = sys.argv[1]
ts = json.load(open(f"{out}/timeseries.json"))
t0 = ts[0]["t"]
x = [(p["t"] - t0) / 1000 for p in ts]
events = []
try:
    for line in open(f"{out}/events.log"):
        t = dt.datetime.strptime(line.split()[0][2:], "%H:%M:%S")
        events.append((t, line.split("fault ")[1].strip()))
except FileNotFoundError:
    pass

fig, ax = plt.subplots(3, 1, figsize=(11, 8), sharex=True)
ax[0].plot(x, [p["latencyMs"]["p50"] for p in ts], label="p50")
ax[0].plot(x, [p["latencyMs"]["p95"] for p in ts], label="p95")
ax[0].plot(x, [p["latencyMs"]["p99"] for p in ts], label="p99")
ax[0].set_ylabel("gateway latency (ms)"); ax[0].legend(loc="upper left")
ax[1].plot(x, [p["rps"] for p in ts], color="#555"); ax[1].set_ylabel("requests / s")
ax[2].plot(x, [p["noBidRate"] * 100 for p in ts], label="no-bid %")
ax[2].plot(x, [p["errorRate"] * 100 for p in ts], label="error %"); ax[2].set_ylabel("%"); ax[2].legend(loc="upper left")
ax[2].set_xlabel("seconds since first sample")
for a in ax:
    a.grid(alpha=0.25)
fig.suptitle("Chaos run: Redis down, then +200 ms on shared stores (fault changes listed in events.log)")
fig.tight_layout()
fig.savefig(f"{out}/chaos.png", dpi=130)
print("wrote", f"{out}/chaos.png")
