package io.adexchange.freqcap;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Bucketed sliding window: {@code windowMillis / bucketMillis} buckets per (user,campaign).
 * Accuracy: a count ages out at bucket granularity (error <= one bucket of window).
 */
public final class InMemoryFrequencyCapStore implements FrequencyCapStore {

    private static final class Counter {
        final long[] ids;
        final int[] counts;

        Counter(int n) {
            ids = new long[n];
            counts = new int[n];
            java.util.Arrays.fill(ids, -1);
        }
    }

    private final long bucketMillis;
    private final int buckets;
    private final ConcurrentHashMap<String, Counter> map = new ConcurrentHashMap<>();

    public InMemoryFrequencyCapStore(long windowMillis, long bucketMillis) {
        this.bucketMillis = bucketMillis;
        this.buckets = (int) Math.max(1, windowMillis / bucketMillis);
    }

    private static String key(String user, String campaign) {
        return user + '|' + campaign;
    }

    @Override
    public boolean check(String userId, String[] campaignIds, int[] caps, int n, boolean[] capped, long now) {
        long cur = now / bucketMillis;
        for (int i = 0; i < n; i++) {
            capped[i] = false;
            if (caps[i] <= 0) continue;
            Counter c = map.get(key(userId, campaignIds[i]));
            if (c == null) continue;
            int sum = 0;
            synchronized (c) {
                for (int b = 0; b < buckets; b++) {
                    if (c.ids[b] > cur - buckets && c.ids[b] <= cur) sum += c.counts[b];
                }
            }
            capped[i] = sum >= caps[i];
        }
        return true;
    }

    @Override
    public void record(String userId, String campaignId, long now) {
        long cur = now / bucketMillis;
        int slot = (int) (cur % buckets);
        Counter c = map.computeIfAbsent(key(userId, campaignId), k -> new Counter(buckets));
        synchronized (c) {
            if (c.ids[slot] != cur) {
                c.ids[slot] = cur;
                c.counts[slot] = 0;
            }
            c.counts[slot]++;
        }
    }
}
