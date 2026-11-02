package io.adexchange.budget;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the Lua scripts against a real Redis and checks parity with the in-memory reference. */
@Testcontainers(disabledWithoutDocker = true)
class RedisLeaseStoreIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static RedisLeaseStore store;

    @BeforeAll
    static void connect() {
        store = new RedisLeaseStore("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @AfterAll
    static void close() {
        store.close();
    }

    @Test
    void leaseLifecycleMatchesReference() {
        store.registerBudget("c1", 100_000);
        var g1 = store.acquire("c1", "a", 60_000, 0, 10_000);
        var g2 = store.acquire("c1", "b", 60_000, 0, 10_000);
        assertThat(g1.amountMicros()).isEqualTo(60_000);
        assertThat(g2.amountMicros()).as("only remainder granted").isEqualTo(40_000);
        assertThat(store.acquire("c1", "c", 1, 0, 10_000).granted()).isFalse();

        store.release("c1", "a", 10_000, 1);
        assertThat(store.available("c1", 1)).isEqualTo(50_000);
        store.release("c1", "a", 10_000, 2); // idempotent
        assertThat(store.available("c1", 2)).isEqualTo(50_000);

        store.settle("c1", "b", 30_000, 3);
        assertThat(store.available("c1", 10_000)).as("b reclaimed at expiry, only unreported returned").isEqualTo(60_000);
        store.release("c1", "b", 40_000, 10_001); // late close of reclaimed lease charges the extra back
        assertThat(store.committed("c1", 10_001)).isEqualTo(50_000);
    }

    @Test
    void concurrentAcquireNeverGrantsMoreThanBudget() throws Exception {
        store.registerBudget("c2", 1_000_000);
        var threads = new java.util.ArrayList<Thread>();
        var total = new java.util.concurrent.atomic.AtomicLong();
        for (int t = 0; t < 8; t++) {
            int id = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    total.addAndGet(store.acquire("c2", "t" + id + "-" + i, 30_000, 0, 60_000).amountMicros());
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) th.join();
        assertThat(total.get()).isEqualTo(1_000_000);
        assertThat(store.available("c2", 0)).isZero();
    }
}
