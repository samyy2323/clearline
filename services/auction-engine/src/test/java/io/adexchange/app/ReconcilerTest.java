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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcilerTest {
    static final long LAG = 10_000;

    ManualClock clock = new ManualClock(1_000_000);
    InMemoryLeaseStore store = new InMemoryLeaseStore();
    LeaseManager leases = new LeaseManager(store, clock, "eng", 100_000, 3_600_000, 1_000, 0.4);
    IndexHolder index = new IndexHolder();
    Map<String, Long> billed = new HashMap<>();
    Reconciler recon;

    @BeforeEach
    void setUp() {
        ConfirmedSpendSource src = new ConfirmedSpendSource() {
            public Map<String, Long> billedByCampaign() { return new HashMap<>(billed); }
            public String name() { return "test"; }
        };
        ExchangeProperties props = new ExchangeProperties("lite", null, null, null, null, null, LAG, null, 0, 0, null, null, null, null, null,
                0, 0, null, null, false, 1, 0, false);
        recon = new Reconciler(leases, store, src, index, props, new SimpleMeterRegistry());
        store.registerBudget("c", 1_000_000);
        index.upsert(new Campaign("c", "adv-1", "n", 5_000_000, 1_000_000, Set.of(), Set.of(), Set.of(), Set.of(), 0, Set.of(), 0, 0, Long.MAX_VALUE, true));
        leases.activate("c");
    }

    /** Spends through several leases so that part of the spend lives in CLOSED leases (credit never touches open leases). */
    long bookSpend(long total) {
        long spent = 0;
        while (spent < total) {
            if (leases.trySpend("c", 10_000)) spent += 10_000;
            else leases.tick();
        }
        leases.tick();
        leases.tick();
        return spent;
    }

    @Test
    void driftIsTheGapBetweenBookedAndBilledAndNothingIsCreditedInsideTheTokenLifetime() {
        long booked = bookSpend(300_000);
        billed.put("c", 200_000L);
        recon.reconcile(clock.nowMillis());
        assertThat(recon.status().get("driftMicros")).isEqualTo(booked - 200_000);
        clock.advance(LAG / 2);
        long committedBefore = store.committed("c", clock.nowMillis());
        recon.reconcile(clock.nowMillis());
        assertThat(recon.status().get("creditedMicros")).as("too early: unbilled impressions may still arrive").isEqualTo(0L);
        assertThat(store.committed("c", clock.nowMillis())).isEqualTo(committedBefore);
    }

    @Test
    void bookingsOlderThanTheTokenLifetimeThatWereNeverBilledAreCreditedBackOnce() {
        long booked = bookSpend(300_000);
        billed.put("c", 200_000L);
        recon.reconcile(clock.nowMillis());                    // records booked spend at t0
        clock.advance(LAG + 1);
        long committedBefore = store.committed("c", clock.nowMillis());
        recon.reconcile(clock.nowMillis());
        long credited = (long) recon.status().get("creditedMicros");
        assertThat(credited).as("never more than booked-and-expired minus billed").isBetween(1L, booked - 200_000);
        assertThat(store.committed("c", clock.nowMillis())).isEqualTo(committedBefore - credited);
        recon.reconcile(clock.nowMillis() + 1);
        assertThat(recon.status().get("creditedMicros")).as("idempotent: the same drift is not credited twice").isEqualTo(credited);
    }

    @Test
    void whenBillingCatchesUpNothingIsCredited() {
        bookSpend(300_000);
        billed.put("c", leases.totalSpent("c"));
        recon.reconcile(clock.nowMillis());
        clock.advance(LAG + 1);
        recon.reconcile(clock.nowMillis());
        assertThat(recon.status().get("creditedMicros")).isEqualTo(0L);
        assertThat(recon.status().get("driftMicros")).isEqualTo(0L);
    }

    @Test
    void anUnavailableConfirmedSpendSourceSkipsTheRunInsteadOfCreditingOnBadData() {
        bookSpend(200_000);
        ConfirmedSpendSource broken = new ConfirmedSpendSource() {
            public Map<String, Long> billedByCampaign() { throw new IllegalStateException("clickhouse down"); }
            public String name() { return "broken"; }
        };
        ExchangeProperties props = new ExchangeProperties("lite", null, null, null, null, null, LAG, null, 0, 0, null, null, null, null, null,
                0, 0, null, null, false, 1, 0, false);
        Reconciler r = new Reconciler(leases, store, broken, index, props, new SimpleMeterRegistry());
        r.reconcile(clock.nowMillis());
        clock.advance(LAG + 1);
        r.reconcile(clock.nowMillis());
        assertThat(r.status().get("creditedMicros")).isEqualTo(0L);
        assertThat((String) r.status().get("lastError")).contains("clickhouse down");
    }
}
