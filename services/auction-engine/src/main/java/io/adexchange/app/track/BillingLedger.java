package io.adexchange.app.track;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Confirmed-spend ledger. An impression id is billed at most once, however many times it is delivered. */
public final class BillingLedger {
    private final RotatingDedupe seen;
    private final Map<String, LongAdder> billedByCampaign = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> billedByArm = new ConcurrentHashMap<>();
    private final LongAdder total = new LongAdder();
    /** Billed spend per campaign keyed by BOOKING second, so spend can be totalled for bookings made before a cutoff (exact reconciliation). */
    private final Map<String, ConcurrentSkipListMap<Long, LongAdder>> byBookingSecond = new ConcurrentHashMap<>();
    private static final int KEEP_SECONDS = 4000;

    public BillingLedger(int dedupeCapacity) {
        this.seen = new RotatingDedupe(dedupeCapacity);
    }

    /** @return true if billed now, false if this impression id was already billed */
    public boolean billImpression(String eventId, String campaignId, String arm, long micros, long bookedMs) {
        if (!seen.addIfAbsent(eventId)) return false;
        billedByCampaign.computeIfAbsent(campaignId, k -> new LongAdder()).add(micros);
        billedByArm.computeIfAbsent(arm, k -> new LongAdder()).add(micros);
        total.add(micros);
        var buckets = byBookingSecond.computeIfAbsent(campaignId, k -> new ConcurrentSkipListMap<>());
        buckets.computeIfAbsent(bookedMs / 1000, k -> new LongAdder()).add(micros);
        if (buckets.size() > KEEP_SECONDS + 500) compact(buckets);
        return true;
    }

    /** Old seconds are folded into one bucket: reconciliation only asks about cutoffs a full token lifetime in the past (lifetime must stay under ~1 h). */
    private static void compact(ConcurrentSkipListMap<Long, LongAdder> m) {
        synchronized (m) {
            if (m.size() <= KEEP_SECONDS) return;
            long fold = 0, lastKey = Long.MIN_VALUE;
            int remove = m.size() - KEEP_SECONDS;
            for (var e : m.entrySet()) {
                if (remove-- <= 0) break;
                fold += e.getValue().sum();
                lastKey = e.getKey();
            }
            if (lastKey != Long.MIN_VALUE) {
                final long key = lastKey;
                m.headMap(key, true).clear();
                m.computeIfAbsent(key, k -> new LongAdder()).add(fold);
            }
        }
    }

    /** Billed spend, per campaign, for bookings made at or before {@code tMs}. */
    public Map<String, Long> billedForBookingsUpTo(long tMs) {
        Map<String, Long> out = new HashMap<>();
        byBookingSecond.forEach((c, m) -> {
            long sum = 0;
            for (LongAdder a : m.headMap(tMs / 1000, true).values()) sum += a.sum();
            out.put(c, sum);
        });
        return out;
    }

    public boolean hasImpression(String eventId) {
        return seen.contains(eventId);
    }

    public long billed(String campaignId) {
        LongAdder a = billedByCampaign.get(campaignId);
        return a == null ? 0 : a.sum();
    }

    public long billedByArm(String arm) {
        LongAdder a = billedByArm.get(arm);
        return a == null ? 0 : a.sum();
    }

    public long total() {
        return total.sum();
    }

    public Map<String, LongAdder> byCampaign() {
        return billedByCampaign;
    }
}
