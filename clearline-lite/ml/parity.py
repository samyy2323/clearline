"""Writes ml/parity_vectors.json; the Java build checks PctrModel against it so training and serving cannot drift apart."""
import json
import os

from hashing import fnv1a, index


def signed(x: int) -> int:
    return x - (1 << 32) if x >= 1 << 31 else x


strings = ["pub=pub12", "cat=news", "dev=mobile", "geo=IN", "hr=14", "seg=sports", "c0-007", "c1-039", "", "x"]
vec = {
    "fnv1a": [{"s": s, "h": signed(fnv1a(s))} for s in strings],
    "index": [
        {"f": signed(fnv1a(f)), "c": signed(fnv1a(c)), "idx": index(fnv1a(f), fnv1a(c))}
        for f in ["pub=pub12", "cat=news", "seg=sports", "hr=3"]
        for c in ["c0-007", "c1-039"]
    ]
    + [{"f": 0, "c": signed(fnv1a("c0-007")), "idx": index(0, fnv1a("c0-007"))}],
}
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "parity_vectors.json")
json.dump(vec, open(out, "w"), indent=1)
print("wrote", out)
