package io.adexchange.app;

import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.app.recon.ConfirmedSpendSource;
import io.adexchange.app.recon.Reconciler;
import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.Campaign;
import io.adexchange.budget.InMemoryLeaseStore;
import io.adexchange.budget.LeaseManager;
import io.adexchange.clock.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcilerTest {
    static final long TTL = 10_000;
    static final long T0 = 1_000_000;

    ManualClock clock = new ManualClock(T0);
    InMemoryLeaseStore store = new InMemoryLeaseStore();
    LeaseManager leases = new LeaseManager(store, clock, "eng", 100_000, 3_600_000, 1_000, 0.4);
    IndexHolder index = new IndexHolder();
    /** every bill the "analytics store" has received: {bookedMs, micros} */
    List<long[]> bills = new ArrayList<>();
    boolean sourceDown;
    Reconciler recon;

    ConfirmedSpendSource source = new ConfirmedSpendSource() {
        public Map<String, Long> billedByCampaign() {
            if (sourceDown) throw new IllegalStateException("clickhouse down");
            return Map.of("c", bills.stream().mapToLong(b -> b[1]).sum());
        }

        public Map<String, Long> billedForBookingsUpTo(long tMs) {
            if (sourceDown) throw new IllegalStateException("clickhouse down");
            return Map.of("c", bills.stream().filter(b -> b[0] <= tMs).mapToLong(b -> b[1]).sum());
        }

        public String name() { return "test"; }
    };

    static ExchangeProperties props() {
        return new ExchangeProperties("lite", null, null, null, null, null, TTL, null, 0, 0, null, null, null, null, null,
                0, 0, null, null, false, 1, 0, false);
    }

    @BeforeEach
    void setUp() {
        recon = new Reconciler(leases, store, source, index, props(), new SimpleMeterRegistry());
        store.registerBudget("c", 1_000_000);
        index.upsert(new Campaign("c", "adv-1", "n", 5_000_000, 1_000_000, Set.of(), Set.of(), Set.of(), Set.of(), 0, Set.of(), 0, 0, Long.MAX_VALUE, true));
        leases.activate("c");
    }

    /** Books {@code total} micros through several leases (credit only ever touches CLOSED leases) and bills {@code billed} of it at the booking time. */
    void book(long total, long billed) {
        long spent = 0;
        while (spent < total) {
            if (leases.trySpend("c", 10_000)) spent += 10_000;
            else leases.tick();
        }
        leases.tick();
        leases.tick();
        if (billed > 0) bills.add(new long[]{clock.nowMillis(), billed});
    }

    @Test
    void creditIsExactEvenWhileNewerBookingsKeepBeingBilled() {
        book(300_000, 200_000);                         // 100k booked at T0 will never be billed
        recon.reconcile(clock.nowMillis());
        clock.advance(6_000);
        book(100_000, 100_000);                         // steady traffic: the newer booking is fully billed
        recon.reconcile(clock.nowMillis());
        clock.advance(TTL - 6_000 + 1);                 // T0's tokens have all expired; the newer booking's have not

        long committedBefore = store.committed("c", clock.nowMillis());
        recon.reconcile(clock.nowMillis());

        assertThat(recon.status().get("creditedMicros")).as("exactly the booked-and-never-billed 100k").isEqualTo(100_000L);
        assertThat(store.committed("c", clock.nowMillis())).isEqualTo(committedBefore - 100_000);
    }

    @Test
    void nothingIsCreditedInsideTheTokenLifetime() {
        book(300_000, 200_000);
        recon.reconcile(clock.nowMillis());
        clock.advance(TTL / 2);
        long committedBefore = store.committed("c", clock.nowMillis());
        recon.reconcile(clock.nowMillis());
        assertThat(recon.status().get("creditedMicros")).as("unbilled impressions may still arrive").isEqualTo(0L);
        assertThat(store.committed("c", clock.nowMillis())).isEqualTo(committedBefore);
        assertThat((long) recon.status().get("driftMicros")).as("drift is reported regardless").isEqualTo(100_000L);
    }

    @Test
    void creditIsAppliedOnceAndIsIdempotent() {
        book(300_000, 200_000);
        recon.reconcile(clock.nowMillis());
        clock.advance(TTL + 1);
        recon.reconcile(clock.nowMillis());
        long first = (long) recon.status().get("creditedMicros");
        recon.reconcile(clock.nowMillis() + 1_000);
        recon.reconcile(clock.nowMillis() + 2_000);
        assertThat(first).isEqualTo(100_000L);
        assertThat(recon.status().get("creditedMicros")).isEqualTo(first);
    }

    @Test
    void whenEverythingBookedWasBilledNothingIsCredited() {
        book(300_000, 300_000);
        recon.reconcile(clock.nowMillis());
        clock.advance(TTL + 1);
        recon.reconcile(clock.nowMillis());
        assertThat(recon.status().get("creditedMicros")).isEqualTo(0L);
        assertThat(recon.status().get("driftMicros")).isEqualTo(0L);
    }

    @Test
    void anUnavailableSourceSkipsTheRunInsteadOfCreditingOnBadData() {
        book(300_000, 200_000);
        recon.reconcile(clock.nowMillis());
        clock.advance(TTL + 1);
        sourceDown = true;
        recon.reconcile(clock.nowMillis());
        assertThat(recon.status().get("creditedMicros")).isEqualTo(0L);
        assertThat((String) recon.status().get("lastError")).contains("clickhouse down");
        sourceDown = false;
        recon.reconcile(clock.nowMillis() + 1);
        assertThat(recon.status().get("creditedMicros")).as("recovers on the next run").isEqualTo(100_000L);
    }
}
