package io.adexchange.freqcap;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A local count-min tier in front of a remote frequency-cap store, to cut remote lookups.
 *
 * <p>Because the sketch can only over-count, {@code estimate < cap} PROVES the user is below the cap as far as this instance has seen, so the
 * candidate is answered locally without touching Redis. Only candidates whose estimate has reached the cap (a true cap or a hash collision)
 * go to the remote store, and the remote's answer is authoritative. Collisions therefore cost an extra lookup, never a wrong answer.
 *
 * <p>The accuracy caveat is across instances: the sketch only knows impressions recorded by THIS instance, so with N engines a user's global
 * count can exceed what any one sketch sees and a cap can be overshot (by up to roughly N-1 impressions per user in the worst case). Route a
 * user to the same engine (hash on user id) or accept that bounded over-delivery. Writes are always forwarded, so Redis stays complete.
 */
public final class TieredFrequencyCap implements FrequencyCapStore {

    private final FrequencyCapStore remote;
    private final CountMinSketch sketch;

    public final AtomicLong answeredLocally = new AtomicLong();
    public final AtomicLong sentToRemote = new AtomicLong();

    public TieredFrequencyCap(FrequencyCapStore remote, int depth, int widthPowerOfTwo, int buckets, long bucketMillis) {
        this.remote = remote;
        this.sketch = new CountMinSketch(depth, widthPowerOfTwo, buckets, bucketMillis);
    }

    public long sketchMemoryBytes() {
        return sketch.memoryBytes();
    }

    @Override
    public boolean check(String userId, String[] campaignIds, int[] caps, int n, boolean[] capped, long now) {
        int[] idx = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            capped[i] = false;
            if (caps[i] <= 0) continue;                                           // uncapped campaign
            if (sketch.estimate(userId, campaignIds[i], now) < caps[i]) {         // provably below the cap here
                answeredLocally.incrementAndGet();
                continue;
            }
            idx[m++] = i;
        }
        if (m == 0) return true;                                                  // no remote round trip at all
        String[] ids = new String[m];
        int[] cs = new int[m];
        boolean[] out = new boolean[m];
        for (int k = 0; k < m; k++) {
            ids[k] = campaignIds[idx[k]];
            cs[k] = caps[idx[k]];
        }
        sentToRemote.addAndGet(m);
        boolean ok = remote.check(userId, ids, cs, m, out, now);
        for (int k = 0; k < m; k++) capped[idx[k]] = out[k];
        return ok;
    }

    @Override
    public void record(String userId, String campaignId, long now) {
        sketch.add(userId, campaignId, now);
        remote.record(userId, campaignId, now);
    }

    public Map<String, Object> stats() {
        long local = answeredLocally.get(), rem = sentToRemote.get();
        return Map.of("tier", "count-min", "answeredLocally", local, "sentToRemote", rem,
                "remoteShare", local + rem == 0 ? 0.0 : (double) rem / (local + rem), "sketchMemoryBytes", sketch.memoryBytes());
    }
}
