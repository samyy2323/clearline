package io.adexchange.budget;

import io.adexchange.clock.ManualClock;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant: true spend never exceeds {@code budget + chunk * (instances + crashes)}.
 * Without crashes the only slack is the transient window in which an instance has spent but not yet
 * settled (at most one lease per instance); a crash can additionally lose one lease worth of unreported spend.
 */
class OverspendInvariantProperties {

    static final String C = "camp";
    static final long BUDGET = 100_000, CHUNK = 10_000, TTL = 1_000, SKEW = 100;
    static final int N = 3;

    record Action(int op, int inst, long amount) {}

    @Provide
    Arbitrary<List<Action>> actions() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 9),
                Arbitraries.integers().between(0, N - 1),
                Arbitraries.longs().between(1, 3_000)).as(Action::new).list().ofMinSize(1).ofMaxSize(400);
    }

    @Property(tries = 500)
    void overspendIsBoundedByLeaseSizeTimesInstances(@ForAll("actions") List<Action> actions) {
        ManualClock clock = new ManualClock(1_000_000);
        InMemoryLeaseStore store = new InMemoryLeaseStore();
        store.registerBudget(C, BUDGET);
        LeaseManager[] m = new LeaseManager[N];
        int[] gen = new int[N];
        for (int i = 0; i < N; i++) m[i] = fresh(store, clock, i, gen[i]);

        long truth = 0;
        int crashes = 0;
        for (Action a : actions) {
            switch (a.op()) {
                case 0, 1, 2, 3, 4, 5 -> {
                    if (m[a.inst()].trySpend(C, a.amount())) truth += a.amount();
                }
                case 6, 7 -> {
                    clock.advance(a.amount() % (TTL / 4) + 1);
                    for (LeaseManager x : m) x.tick();
                }
                case 8 -> {
                    m[a.inst()].crash();
                    crashes++;
                    m[a.inst()] = fresh(store, clock, a.inst(), ++gen[a.inst()]);
                }
                default -> {
                    m[a.inst()].shutdown();
                    m[a.inst()] = fresh(store, clock, a.inst(), ++gen[a.inst()]);
                }
            }
            assertThat(truth).isLessThanOrEqualTo(BUDGET + CHUNK * (N + crashes));
        }
    }

    @Property(tries = 300)
    void withoutCrashesAndWithPromptSettleTheLedgerNeverOverspends(@ForAll("actions") List<Action> actions) {
        ManualClock clock = new ManualClock(1_000_000);
        InMemoryLeaseStore store = new InMemoryLeaseStore();
        store.registerBudget(C, BUDGET);
        List<LeaseManager> m = new ArrayList<>();
        for (int i = 0; i < N; i++) m.add(fresh(store, clock, i, 0));
        long truth = 0;
        for (Action a : actions) {
            if (a.op() < 8) {
                if (m.get(a.inst()).trySpend(C, a.amount())) truth += a.amount();
            } else {
                clock.advance(a.amount() % (TTL / 4) + 1);
            }
            // settle everyone after each step (scheduler faster than traffic)
            for (LeaseManager x : m) x.tick();
            assertThat(truth).isLessThanOrEqualTo(BUDGET);
        }
    }

    static LeaseManager fresh(InMemoryLeaseStore store, ManualClock clock, int i, int gen) {
        LeaseManager lm = new LeaseManager(store, clock, "e" + i + "g" + gen, CHUNK, TTL, SKEW, 0.3);
        lm.activate(C);
        return lm;
    }
}
