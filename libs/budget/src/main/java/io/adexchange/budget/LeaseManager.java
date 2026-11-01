package io.adexchange.budget;

import io.adexchange.clock.AdClock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-instance local spender. {@link #trySpend} is lock-free on the fast path and never touches the network;
 * {@link #tick} (called by a background scheduler) settles spend, renews leases ahead of exhaustion
 * and returns closed leases to the store. If the store is unreachable the engine keeps spending its
 * local lease, then fails closed (trySpend returns false).
 */
public final class LeaseManager {

    private static final long CLOSED = Long.MIN_VALUE / 2;

    static final class Local {
        final String id;
        final long granted;
        final long expiresAt;
        final AtomicLong remaining;

        Local(String id, long granted, long expiresAt) {
            this.id = id;
            this.granted = granted;
            this.expiresAt = expiresAt;
            this.remaining = new AtomicLong(granted);
        }

        long spent() {
            long r = remaining.get();
            return granted - (r < 0 ? frozenRemaining : r);
        }

        // set when closed so spent() is stable
        volatile long frozenRemaining;

        long close() {
            long r = remaining.getAndSet(CLOSED);
            if (r > CLOSED / 2) frozenRemaining = r;
            return granted - frozenRemaining;
        }
    }

    private static final class Account {
        final AtomicReference<Local> current = new AtomicReference<>();
        final AtomicReference<Local> next = new AtomicReference<>();
        final ConcurrentLinkedQueue<Local> closed = new ConcurrentLinkedQueue<>();
        final AtomicLong closedSpent = new AtomicLong();
    }

    private final LeaseStore store;
    private final AdClock clock;
    private final String instanceId;
    private final long chunkMicros;
    private final long ttlMillis;
    private final long skewMillis;
    private final double renewBelow;
    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public final AtomicLong spendApproved = new AtomicLong();
    public final AtomicLong spendDenied = new AtomicLong();
    public final AtomicLong renewFailures = new AtomicLong();
    public final AtomicLong leasesAcquired = new AtomicLong();

    public LeaseManager(LeaseStore store, AdClock clock, String instanceId,
                        long chunkMicros, long ttlMillis, long skewMillis, double renewBelowFraction) {
        this.store = store;
        this.clock = clock;
        this.instanceId = instanceId;
        this.chunkMicros = chunkMicros;
        this.ttlMillis = ttlMillis;
        this.skewMillis = skewMillis;
        this.renewBelow = renewBelowFraction;
    }

    /** Registers a campaign and synchronously fetches its first lease. */
    public void activate(String campaignId) {
        Account a = accounts.computeIfAbsent(campaignId, k -> new Account());
        if (a.current.get() == null) acquireInto(campaignId, a);
    }

    public void deactivate(String campaignId) {
        Account a = accounts.remove(campaignId);
        if (a != null) closeAll(campaignId, a);
    }

    private boolean acquireInto(String campaignId, Account a) {
        long now = clock.nowMillis();
        String id = instanceId + "-" + seq.incrementAndGet();
        LeaseStore.Grant g = store.acquire(campaignId, id, chunkMicros, now, ttlMillis);
        if (!g.granted()) return false;
        leasesAcquired.incrementAndGet();
        // stop spending before the store may reclaim the lease
        Local l = new Local(id, g.amountMicros(), g.expiresAtMillis() - skewMillis);
        if (!a.current.compareAndSet(null, l)) a.next.set(l);
        return true;
    }

    /** Hot path: debit {@code micros} from the local lease. No network. */
    public boolean trySpend(String campaignId, long micros) {
        Account a = accounts.get(campaignId);
        if (a == null) {
            spendDenied.incrementAndGet();
            return false;
        }
        for (int i = 0; i < 4; i++) {
            Local cur = a.current.get();
            if (cur == null) break;
            if (clock.nowMillis() < cur.expiresAt) {
                long rem = cur.remaining.get();
                if (rem >= micros) {
                    if (cur.remaining.compareAndSet(rem, rem - micros)) {
                        spendApproved.incrementAndGet();
                        return true;
                    }
                    continue;
                }
            }
            if (!rotate(a, cur)) break;
        }
        spendDenied.incrementAndGet();
        return false;
    }

    private boolean rotate(Account a, Local cur) {
        Local n = a.next.get();
        if (n == null) return false;
        synchronized (a) {
            if (a.current.get() != cur) return true;
            a.next.set(null);
            a.current.set(n);
            cur.close();
            a.closedSpent.addAndGet(cur.spent());
            a.closed.add(cur);
        }
        return true;
    }

    public long localRemaining(String campaignId) {
        Account a = accounts.get(campaignId);
        if (a == null) return 0;
        Local c = a.current.get();
        Local n = a.next.get();
        long r = (c == null || clock.nowMillis() >= c.expiresAt) ? 0 : Math.max(0, c.remaining.get());
        return r + (n == null ? 0 : n.remaining.get());
    }

    /** Total spent through this manager's leases (approved spends). */
    public long totalSpent(String campaignId) {
        Account a = accounts.get(campaignId);
        if (a == null) return 0;
        long s = a.closedSpent.get();
        Local c = a.current.get();
        if (c != null) s += c.spent();
        return s;
    }

    /** Background maintenance. Safe to call from a single scheduler thread. */
    public void tick() {
        accounts.forEach((cid, a) -> {
            try {
                Local cur = a.current.get();
                if (cur != null && clock.nowMillis() >= cur.expiresAt) rotate(a, cur);
                drainClosed(cid, a);
                cur = a.current.get();
                if (cur != null) store.settle(cid, cur.id, cur.spent(), clock.nowMillis());
                boolean low = cur == null || cur.remaining.get() < renewBelow * cur.granted
                        || clock.nowMillis() >= cur.expiresAt - ttlMillis / 4;
                if (low && a.next.get() == null) acquireInto(cid, a);
            } catch (RuntimeException e) {
                renewFailures.incrementAndGet();
            }
        });
    }

    private void drainClosed(String cid, Account a) {
        Local l;
        while ((l = a.closed.peek()) != null) {
            store.release(cid, l.id, l.spent(), clock.nowMillis());
            a.closed.poll();
        }
    }

    private void closeAll(String cid, Account a) {
        for (Local l : new Local[]{a.current.getAndSet(null), a.next.getAndSet(null)}) {
            if (l != null) {
                l.close();
                a.closedSpent.addAndGet(l.spent());
                a.closed.add(l);
            }
        }
        try {
            drainClosed(cid, a);
        } catch (RuntimeException e) {
            renewFailures.incrementAndGet();
        }
    }

    /** Graceful shutdown: return all unused lease budget. */
    public void shutdown() {
        accounts.forEach((cid, a) -> closeAll(cid, a));
        accounts.clear();
    }

    /** Test hook: simulate a hard crash (no settle, no release). */
    public void crash() {
        accounts.clear();
    }
}
