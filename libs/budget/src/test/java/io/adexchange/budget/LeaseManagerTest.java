package io.adexchange.budget;

import io.adexchange.clock.ManualClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseManagerTest {

    @Test
    void concurrentSpendNeverExceedsBudgetAndStrandsAtMostOneLeasePerInstance() throws Exception {
        long budget = 1_000_000, chunk = 50_000;
        int instances = 4;
        ManualClock clock = new ManualClock(0);
        InMemoryLeaseStore store = new InMemoryLeaseStore();
        store.registerBudget("c", budget);
        AtomicLong spent = new AtomicLong();
        List<LeaseManager> ms = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            LeaseManager m = new LeaseManager(store, clock, "e" + i, chunk, 3_600_000, 1_000, 0.3);
            m.activate("c");
            ms.add(m);
        }
        ExecutorService pool = Executors.newFixedThreadPool(instances * 2);
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(() -> ms.forEach(LeaseManager::tick), 0, 1, TimeUnit.MILLISECONDS);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < instances * 2; t++) {
            LeaseManager m = ms.get(t % instances);
            fs.add(pool.submit(() -> {
                long lastOk = System.nanoTime();
                while (System.nanoTime() - lastOk < 500_000_000L) { // give the async renewer 500ms to refill
                    if (m.trySpend("c", 37)) {
                        spent.addAndGet(37);
                        lastOk = System.nanoTime();
                    } else {
                        Thread.onSpinWait();
                    }
                }
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        ticker.shutdownNow();
        pool.shutdownNow();
        assertThat(spent.get()).isLessThanOrEqualTo(budget);
        assertThat(spent.get()).as("stranded budget bounded").isGreaterThanOrEqualTo(budget - chunk * instances);
    }

    @Test
    void gracefulShutdownReturnsUnusedLease() {
        ManualClock clock = new ManualClock(0);
        InMemoryLeaseStore store = new InMemoryLeaseStore();
        store.registerBudget("c", 100_000);
        LeaseManager m = new LeaseManager(store, clock, "e", 40_000, 60_000, 1_000, 0.3);
        m.activate("c");
        assertThat(m.trySpend("c", 10_000)).isTrue();
        assertThat(store.available("c", 0)).isEqualTo(60_000);
        m.shutdown();
        assertThat(store.available("c", 0)).isEqualTo(90_000);
    }

    @Test
    void crashedLeaseIsReclaimedAfterTtl() {
        ManualClock clock = new ManualClock(0);
        InMemoryLeaseStore store = new InMemoryLeaseStore();
        store.registerBudget("c", 100_000);
        LeaseManager m = new LeaseManager(store, clock, "e", 40_000, 60_000, 1_000, 0.3);
        m.activate("c");
        m.tick(); // settles 0
        m.crash();
        assertThat(store.available("c", 1_000)).isEqualTo(60_000);
        assertThat(store.available("c", 60_000)).as("reclaimed after ttl").isEqualTo(100_000);
    }

    @Test
    void stopsSpendingBeforeStoreExpiryAndFailsClosedWhenStoreIsDown() {
        ManualClock clock = new ManualClock(0);
        InMemoryLeaseStore real = new InMemoryLeaseStore();
        real.registerBudget("c", 1_000_000);
        boolean[] down = {false};
        LeaseStore flaky = new LeaseStore() {
            public void registerBudget(String c, long b) { real.registerBudget(c, b); }
            public Grant acquire(String c, String l, long r, long n, long t) {
                if (down[0]) throw new IllegalStateException("redis down");
                return real.acquire(c, l, r, n, t);
            }
            public void settle(String c, String l, long s, long n) {
                if (down[0]) throw new IllegalStateException("redis down");
                real.settle(c, l, s, n);
            }
            public void release(String c, String l, long s, long n) {
                if (down[0]) throw new IllegalStateException("redis down");
                real.release(c, l, s, n);
            }
            public long available(String c, long n) { return real.available(c, n); }
            public long committed(String c, long n) { return real.committed(c, n); }
        };
        LeaseManager m = new LeaseManager(flaky, clock, "e", 10_000, 60_000, 5_000, 0.3);
        m.activate("c");
        down[0] = true;
        m.tick();
        assertThat(m.renewFailures.get()).isPositive();
        assertThat(m.trySpend("c", 9_000)).as("continues on local lease").isTrue();
        assertThat(m.trySpend("c", 9_000)).as("lease exhausted -> fail closed").isFalse();
        clock.advance(56_000); // past local expiry (60_000 - skew)
        assertThat(m.trySpend("c", 1)).isFalse();
    }
}
