package io.adexchange.freqcap;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Windowed count-min sketch over (user, campaign) keys: a ring of time buckets, each a {@code depth x width} counter table.
 *
 * <p>Guarantee: {@link #estimate} never UNDER-counts what was recorded through this instance (count-min only over-counts, on hash collisions).
 * Memory is {@code buckets x depth x width x 4} bytes regardless of how many users there are, e.g. 24 hourly buckets x 4 rows x 2^16 = 24 MiB.
 */
public final class CountMinSketch {

    private final int depth, width, mask, buckets;
    private final long bucketMillis;
    private final AtomicIntegerArray[] tables;   // one flattened depth*width table per ring slot
    private final long[] slotBucketId;           // which absolute bucket each ring slot currently holds

    public CountMinSketch(int depth, int widthPowerOfTwo, int buckets, long bucketMillis) {
        if (Integer.bitCount(widthPowerOfTwo) != 1) throw new IllegalArgumentException("width must be a power of two");
        this.depth = depth;
        this.width = widthPowerOfTwo;
        this.mask = widthPowerOfTwo - 1;
        this.buckets = buckets;
        this.bucketMillis = bucketMillis;
        this.tables = new AtomicIntegerArray[buckets];
        this.slotBucketId = new long[buckets];
        for (int i = 0; i < buckets; i++) {
            tables[i] = new AtomicIntegerArray(depth * widthPowerOfTwo);
            slotBucketId[i] = -1;
        }
    }

    public long memoryBytes() {
        return (long) buckets * depth * width * 4;
    }

    static long hash(String user, String campaign) {
        long h = 0xcbf29ce484222325L;                       // FNV-1a 64
        for (int i = 0, n = user.length(); i < n; i++) { h ^= user.charAt(i); h *= 0x100000001b3L; }
        h ^= '|'; h *= 0x100000001b3L;
        for (int i = 0, n = campaign.length(); i < n; i++) { h ^= campaign.charAt(i); h *= 0x100000001b3L; }
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;   // final avalanche so the low bits are usable
        return h;
    }

    private int index(long h, int row) {
        int h1 = (int) h, h2 = (int) (h >>> 32) | 1;       // double hashing: row i uses h1 + i*h2
        return row * width + ((h1 + row * h2) & mask);
    }

    public void add(String user, String campaign, long nowMillis) {
        long bucket = nowMillis / bucketMillis;
        int slot = (int) (bucket % buckets);
        synchronized (slotBucketId) {                       // rare: only when the slot rolls over to a new bucket
            if (slotBucketId[slot] != bucket) {
                tables[slot] = new AtomicIntegerArray(depth * width);
                slotBucketId[slot] = bucket;
            }
        }
        long h = hash(user, campaign);
        AtomicIntegerArray t = tables[slot];
        for (int r = 0; r < depth; r++) t.incrementAndGet(index(h, r));
    }

    /** Upper bound on impressions of this (user, campaign) pair recorded through this sketch within the window. */
    public int estimate(String user, String campaign, long nowMillis) {
        long cur = nowMillis / bucketMillis;
        long h = hash(user, campaign);
        int total = 0;
        for (int s = 0; s < buckets; s++) {
            long id = slotBucketId[s];
            if (id <= cur - buckets || id > cur) continue;  // empty or outside the window
            AtomicIntegerArray t = tables[s];
            int min = Integer.MAX_VALUE;
            for (int r = 0; r < depth; r++) min = Math.min(min, t.get(index(h, r)));
            total += min;
        }
        return total;
    }
}
