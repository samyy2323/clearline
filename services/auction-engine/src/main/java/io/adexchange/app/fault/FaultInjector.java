package io.adexchange.app.fault;

import io.adexchange.budget.LeaseStore;
import io.adexchange.freqcap.FrequencyCapStore;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Failure injection for demos and chaos tests: emulates "Redis killed" and "+N ms latency" on the shared stores. */
public final class FaultInjector {
    public final AtomicBoolean redisDown = new AtomicBoolean();
    public final AtomicLong extraLatencyMs = new AtomicLong();

    void before() {
        if (redisDown.get()) throw new IllegalStateException("injected: redis down");
        long l = extraLatencyMs.get();
        if (l > 0) {
            try {
                Thread.sleep(l);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public LeaseStore wrap(LeaseStore d) {
        return new LeaseStore() {
            public void registerBudget(String c, long b) { d.registerBudget(c, b); }
            public Grant acquire(String c, String l, long r, long n, long t) { before(); return d.acquire(c, l, r, n, t); }
            public void settle(String c, String l, long s, long n) { before(); d.settle(c, l, s, n); }
            public void release(String c, String l, long s, long n) { before(); d.release(c, l, s, n); }
            public long credit(String c, long m, long n) { before(); return d.credit(c, m, n); }
            public long available(String c, long n) { return d.available(c, n); }
            public long committed(String c, long n) { return d.committed(c, n); }
        };
    }

    public FrequencyCapStore wrap(FrequencyCapStore d) {
        return new FrequencyCapStore() {
            public boolean check(String u, String[] i, int[] c, int n, boolean[] o, long t) { before(); return d.check(u, i, c, n, o, t); }
            public void record(String u, String c, long t) { before(); d.record(u, c, t); }
        };
    }

    public record State(boolean redisDown, long extraLatencyMs) {}

    public State state() {
        return new State(redisDown.get(), extraLatencyMs.get());
    }
}
