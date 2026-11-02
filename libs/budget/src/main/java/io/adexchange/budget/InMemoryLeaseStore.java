package io.adexchange.budget;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reference implementation; semantics are mirrored 1:1 by the Redis Lua scripts. */
public final class InMemoryLeaseStore implements LeaseStore {

    private static final class Rec {
        final long granted;
        long reported;
        final long expires;

        Rec(long granted, long expires) {
            this.granted = granted;
            this.expires = expires;
        }
    }

    private static final class Campaign {
        long budget;
        long committed;
        final Map<String, Rec> open = new HashMap<>();
        final Map<String, Long> reclaimed = new HashMap<>();
    }

    private final ConcurrentHashMap<String, Campaign> campaigns = new ConcurrentHashMap<>();

    private Campaign c(String id) {
        return campaigns.computeIfAbsent(id, k -> new Campaign());
    }

    private static void reclaim(Campaign c, long now) {
        for (Iterator<Map.Entry<String, Rec>> it = c.open.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().expires <= now) {
                c.committed -= e.getValue().granted - e.getValue().reported;
                c.reclaimed.put(e.getKey(), e.getValue().reported);
                it.remove();
            }
        }
    }

    @Override
    public void registerBudget(String campaignId, long budgetMicros) {
        Campaign c = c(campaignId);
        synchronized (c) {
            c.budget = budgetMicros;
        }
    }

    @Override
    public Grant acquire(String campaignId, String leaseId, long requested, long now, long ttl) {
        Campaign c = c(campaignId);
        synchronized (c) {
            reclaim(c, now);
            long avail = Math.max(0, c.budget - c.committed);
            long g = Math.min(requested, avail);
            long exp = now + ttl;
            if (g > 0) {
                c.open.put(leaseId, new Rec(g, exp));
                c.committed += g;
            }
            return new Grant(g, exp);
        }
    }

    @Override
    public void settle(String campaignId, String leaseId, long spent, long now) {
        Campaign c = c(campaignId);
        synchronized (c) {
            Rec r = c.open.get(leaseId);
            if (r != null) {
                r.reported = Math.max(r.reported, Math.min(spent, r.granted));
            } else {
                lateSpend(c, leaseId, spent, false);
            }
        }
    }

    @Override
    public void release(String campaignId, String leaseId, long spent, long now) {
        Campaign c = c(campaignId);
        synchronized (c) {
            Rec r = c.open.remove(leaseId);
            if (r != null) {
                c.committed -= r.granted - Math.min(Math.max(spent, r.reported), r.granted);
            } else {
                lateSpend(c, leaseId, spent, true);
            }
        }
    }

    /** Spend reported for a lease already reclaimed: charge it back so the ledger converges. */
    private static void lateSpend(Campaign c, String leaseId, long spent, boolean closing) {
        Long tomb = c.reclaimed.get(leaseId);
        if (tomb == null) return;
        long delta = Math.max(0, spent - tomb);
        c.committed += delta;
        if (closing) c.reclaimed.remove(leaseId);
        else c.reclaimed.put(leaseId, Math.max(tomb, spent));
    }

    @Override
    public long credit(String campaignId, long micros, long now) {
        Campaign c = c(campaignId);
        synchronized (c) {
            reclaim(c, now);
            long open = 0;
            for (Rec r : c.open.values()) open += r.granted;
            long amt = Math.min(micros, Math.max(0, c.committed - open));
            c.committed -= amt;
            return amt;
        }
    }

    @Override
    public long available(String campaignId, long now) {
        Campaign c = c(campaignId);
        synchronized (c) {
            reclaim(c, now);
            return Math.max(0, c.budget - c.committed);
        }
    }

    @Override
    public long committed(String campaignId, long now) {
        Campaign c = c(campaignId);
        synchronized (c) {
            reclaim(c, now);
            return c.committed;
        }
    }
}
