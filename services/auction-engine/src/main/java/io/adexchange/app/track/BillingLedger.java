package io.adexchange.app.track;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Confirmed-spend ledger. An impression id is billed at most once, however many times it is delivered. */
public final class BillingLedger {
    private final RotatingDedupe seen;
    private final Map<String, LongAdder> billedByCampaign = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> billedByArm = new ConcurrentHashMap<>();
    private final LongAdder total = new LongAdder();

    public BillingLedger(int dedupeCapacity) {
        this.seen = new RotatingDedupe(dedupeCapacity);
    }

    /** @return true if billed now, false if this impression id was already billed */
    public boolean billImpression(String eventId, String campaignId, String arm, long micros) {
        if (!seen.addIfAbsent(eventId)) return false;
        billedByCampaign.computeIfAbsent(campaignId, k -> new LongAdder()).add(micros);
        billedByArm.computeIfAbsent(arm, k -> new LongAdder()).add(micros);
        total.add(micros);
        return true;
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
