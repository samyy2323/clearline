"""
Turns a JFR recording into: top self-time frames, top inclusive frames in our code, allocation by class and by
allocating frame, GC pause summary, folded stacks and a self-contained flame graph SVG.

    python bench/profile/analyze.py bench/results/profile/baseline
"""
import collections
import html
import re
import subprocess
import sys
from pathlib import Path

d = Path(sys.argv[1])
jfr = d / "recording.jfr"


def events(name, depth=64):
    out = subprocess.run(["jfr", "print", "--events", name, "--stack-depth", str(depth), str(jfr)],
                         capture_output=True, text=True, check=True).stdout
    for block in out.split(name + " {")[1:]:
        yield block


def frames_of(block):
    m = re.search(r"stackTrace = \[(.*?)\n\s*\]", block, re.S)
    if not m:
        return []
    fr = []
    for line in m.group(1).strip().splitlines():
        line = line.strip()
        if not line or line == "...":
            continue
        fr.append(re.sub(r"\s+line:\s*\d+", "", line))
    return fr  # top of stack first


def short(f):
    return re.sub(r"\(.*\)", "", f)


MINE = ("io.adexchange.",)

# ---- CPU ----
folded = collections.Counter()
self_t = collections.Counter()
incl = collections.Counter()
threads = collections.Counter()
total = 0
for b in events("jdk.ExecutionSample"):
    fr = frames_of(b)
    if not fr:
        continue
    total += 1
    t = re.search(r'sampledThread = "([^"]*)"', b)
    threads[re.sub(r"[-\d]+$", "", t.group(1)) if t else "?"] += 1
    folded[";".join(short(f) for f in reversed(fr))] += 1
    self_t[short(fr[0])] += 1
    for f in {short(x) for x in fr}:
        incl[f] += 1

print(f"CPU samples: {total}")
print("\nby thread group:")
for k, v in threads.most_common(8):
    print(f"  {100*v/total:5.1f}%  {k}")
print("\ntop self time:")
for k, v in self_t.most_common(15):
    print(f"  {100*v/total:5.1f}%  {k}")
print("\ntop inclusive (io.adexchange.*):")
n = 0
for k, v in incl.most_common():
    if k.startswith(MINE):
        print(f"  {100*v/total:5.1f}%  {k}")
        n += 1
        if n == 15:
            break

# ---- allocation ----
by_class, by_frame, wsum = collections.Counter(), collections.Counter(), 0
for b in events("jdk.ObjectAllocationSample"):
    w = re.search(r"weight = ([\d.]+) (\w+)", b)
    c = re.search(r"objectClass = (\S+)", b)
    if not (w and c):
        continue
    mult = {"bytes": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}.get(w.group(2), 1)
    wt = float(w.group(1)) * mult
    wsum += wt
    by_class[c.group(1)] += wt
    fr = [short(f) for f in frames_of(b)]
    mine = next((f for f in fr if f.startswith(MINE)), fr[0] if fr else "?")
    by_frame[mine] += wt
print(f"\nallocation sampled weight: {wsum/1e9:.2f} GB")
print("by class:")
for k, v in by_class.most_common(10):
    print(f"  {100*v/wsum:5.1f}%  {k}")
print("by allocating frame (first io.adexchange frame):")
for k, v in by_frame.most_common(10):
    print(f"  {100*v/wsum:5.1f}%  {k}")

# ---- GC ----
pauses = []
for b in events("jdk.GarbageCollection", 1):
    m = re.search(r"sumOfPauses = ([\d.]+) (\w+)", b)
    if m:
        pauses.append(float(m.group(1)) * {"ms": 1, "s": 1000, "us": 0.001, "ns": 1e-6}.get(m.group(2), 1))
if pauses:
    pauses.sort()
    print(f"\nGC: {len(pauses)} collections, total pause {sum(pauses):.1f} ms, max {pauses[-1]:.2f} ms, median {pauses[len(pauses)//2]:.2f} ms")

# ---- outputs ----
(d / "folded.txt").write_text("\n".join(f"{k} {v}" for k, v in folded.most_common()))

tree = {"n": "all", "v": 0, "c": {}}
for stack, v in folded.items():
    node = tree
    node["v"] += v
    for f in stack.split(";"):
        node = node["c"].setdefault(f, {"n": f, "v": 0, "c": {}})
        node["v"] += v

W, H = 1400, 18
rects = []
maxdepth = [0]


def lay(node, x, depth):
    maxdepth[0] = max(maxdepth[0], depth)
    w = node["v"] / tree["v"] * W
    if w < 0.4:
        return
    rects.append((x, depth, w, node["n"], node["v"]))
    cx = x
    for ch in sorted(node["c"].values(), key=lambda c: -c["v"]):
        lay(ch, cx, depth + 1)
        cx += ch["v"] / tree["v"] * W


lay(tree, 0, 0)
height = (maxdepth[0] + 2) * H
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{height}" font-family="monospace" font-size="11">',
       f'<rect width="{W}" height="{height}" fill="#0a0b0d"/>',
       f'<text x="8" y="14" fill="#8b8f98">CPU flame graph, {total} samples (JFR jdk.ExecutionSample); hover for names</text>']
for x, depth, w, name, v in rects:
    y = height - (depth + 1) * H
    mine = name.startswith(MINE)
    fill = "#c9a45c" if mine else ("#4f8a76" if name.startswith(("java.", "jdk.", "sun.")) else "#7d93b2")
    label = html.escape(name.split(".")[-1] if w < 60 else name)
    svg.append(f'<g><title>{html.escape(name)} ({v} samples, {100*v/total:.1f}%)</title>'
               f'<rect x="{x:.1f}" y="{y}" width="{max(w-0.5,0.5):.1f}" height="{H-1}" fill="{fill}" opacity="0.85"/>'
               + (f'<text x="{x+3:.1f}" y="{y+12}" fill="#0a0b0d">{label[:int(w/6.5)]}</text>' if w > 40 else "") + "</g>")
svg.append("</svg>")
(d / "flamegraph.svg").write_text("\n".join(svg), encoding="utf-8")
print(f"\nwrote {d/'flamegraph.svg'} and {d/'folded.txt'}")
