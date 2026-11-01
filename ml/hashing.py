"""Feature hashing identical to io.adexchange.auction.scoring.PctrModel (checked by ml/parity_vectors.json and a Java test)."""
BITS = 18
MASK = (1 << BITS) - 1
M32 = 0xFFFFFFFF


def fnv1a(s: str) -> int:
    h = 0x811C9DC5
    for ch in s:
        h ^= ord(ch) & 0xFF
        h = (h * 0x01000193) & M32
    return h


def fmix(h: int) -> int:
    h ^= h >> 16
    h = (h * 0x85EBCA6B) & M32
    h ^= h >> 13
    h = (h * 0xC2B2AE35) & M32
    h ^= h >> 16
    return h


def index(feature_hash: int, campaign_hash: int) -> int:
    return fmix(feature_hash ^ ((campaign_hash * 0x9E3779B1) & M32)) & MASK


def request_features(pub, cat, dev, geo, hour, segs):
    f = [fnv1a(f"pub={pub}"), fnv1a(f"cat={cat}"), fnv1a(f"dev={dev}"), fnv1a(f"geo={geo}"), fnv1a(f"hr={hour}"), fnv1a(f"ch={cat}|{hour}")]
    f += [fnv1a(f"seg={s}") for s in segs]
    f += [fnv1a(f"sc={s}|{cat}") for s in segs]
    return f


def row_indices(camp, pub, cat, dev, geo, hour, segs):
    c = fnv1a(camp)
    feats = request_features(pub, cat, dev, geo, hour, segs)
    # campaign-only, request x campaign, and request features shared across campaigns (campaign hash 0)
    return [index(0, c)] + [index(f, c) for f in feats] + [index(f, 0) for f in feats]
