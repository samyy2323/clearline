package io.adexchange.app.recon;

import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.Campaign;
import io.adexchange.budget.LeaseManager;
import io.adexchange.budget.LeaseStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reconciles the budget ledger with confirmed spend, exactly.
 *
 * <p>The engine books spend when it AWARDS an impression (so it can never overspend); billing happens only when a signed impression event
 * arrives. Wins that are never rendered, bot traffic, and responses that missed their deadline stay booked but are never billed, so the
 * ledger drifts away from confirmed spend and campaigns under-deliver.
 *
 * <p>Every event carries {@code booked_ms}, the time its spend was booked. A booking made at time {@code P} can no longer be billed once
 * {@code P + tokenTtl} has passed, because its tracking tokens have expired. So for the snapshot {@code P} of the booked counter taken at least one
 * token lifetime ago:
 * <pre>  credit = booked(P) - billedForBookingsUpTo(P + MARGIN) - alreadyCredited</pre>
 * is exactly the spend booked up to {@code P} that will never be billed. {@code MARGIN} covers the few milliseconds between booking a spend
 * and stamping its ticket; bills for bookings inside the margin can only lower the credit, never raise it, so the margin errs on the safe side.
 * The credit goes back to the budget pool through {@code LeaseStore.credit}, which never drops below what open leases still hold.
 *
 * <p>The drift metric is the unconditional gap {@code booked - billed}, which includes impressions still in flight. Single-engine semantics:
 * booked spend is read from this engine's lease manager (it resets on restart; with several engines each needs its own booked figure).
 */
@Component
public class Reconciler {
    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    /** Tolerance between the instant a spend is booked and the instant its ticket is stamped. */
    public static final long MARGIN_MS = 2_000;

    private record Point(long ts, long booked) {}

    private final LeaseManager leases;
    private final LeaseStore store;
    private final ConfirmedSpendSource source;
    private final IndexHolder index;
    private final long ttlMs;

    private final Map<String, ArrayDeque<Point>> history = new HashMap<>();
    private final Map<String, Long> credited = new HashMap<>();

    private final AtomicLong driftMicros = new AtomicLong();
    private final AtomicLong creditedTotal = new AtomicLong();
    private final AtomicLong unbillableMicros = new AtomicLong();
    private volatile long lastRunMs;
    private volatile String lastError = "";
    private volatile long runs;

    public Reconciler(LeaseManager leases, LeaseStore store, ConfirmedSpendSource source, IndexHolder index,
                      ExchangeProperties props, MeterRegistry reg) {
        this.leases = leases;
        this.store = store;
        this.source = source;
        this.index = index;
        this.ttlMs = props.tokenTtlMs();
        Gauge.builder("budget.reconciliation.drift.micros", driftMicros, AtomicLong::get).register(reg);
        Gauge.builder("budget.reconciliation.credited.micros", creditedTotal, AtomicLong::get).register(reg);
        Gauge.builder("budget.reconciliation.unbillable.micros", unbillableMicros, AtomicLong::get).register(reg);
    }

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${exchange.reconcile-interval-ms:60000}")
    public void scheduled() {
        reconcile(System.currentTimeMillis());
    }

    /** One reconciliation pass at wall-clock {@code now} (separate from the scheduler so tests can drive time). */
    public synchronized void reconcile(long now) {
        Map<String, Long> billedAll;
        try {
            billedAll = source.billedByCampaign();
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            log.warn("reconciliation skipped, confirmed-spend source unavailable: {}", e.getMessage());
            return;
        }
        long drift = 0, unbillable = 0, creditedNow = 0;
        long cutoff = now - ttlMs;
        history.keySet().removeIf(id -> index.get(id) == null);

        Map<Long, Map<String, Long>> billedUpTo = new HashMap<>();   // one source query per distinct snapshot time
        for (Campaign c : index.all()) {
            String id = c.id();
            long booked = leases.totalSpent(id);
            drift += Math.max(0, booked - billedAll.getOrDefault(id, 0L));

            ArrayDeque<Point> h = history.computeIfAbsent(id, k -> new ArrayDeque<>());
            h.addLast(new Point(now, booked));
            Point p = null;
            for (Point q : h) if (q.ts() <= cutoff) p = q;
            if (p == null) continue;
            while (h.size() > 1 && h.peekFirst().ts() < p.ts()) h.pollFirst();

            Map<String, Long> byCohort;
            try {
                byCohort = billedUpTo.computeIfAbsent(p.ts(), t -> source.billedForBookingsUpTo(t + MARGIN_MS));
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                continue;
            }
            long never = Math.max(0, p.booked() - byCohort.getOrDefault(id, 0L));   // booked, expired, never billed
            unbillable += never;
            long toCredit = never - credited.getOrDefault(id, 0L);
            if (toCredit > 0) {
                try {
                    long done = store.credit(id, toCredit, now);
                    credited.merge(id, done, Long::sum);
                    creditedNow += done;
                } catch (RuntimeException e) {
                    lastError = e.getMessage();
                }
            }
        }
        creditedTotal.addAndGet(creditedNow);
        driftMicros.set(drift);
        unbillableMicros.set(unbillable);
        lastRunMs = now;
        if (lastError.isEmpty() || creditedNow >= 0) lastError = "";
        runs++;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source.name());
        m.put("mode", "exact (cohorts by booking time)");
        m.put("driftMicros", driftMicros.get());
        m.put("unbillableMicros", unbillableMicros.get());
        m.put("creditedMicros", creditedTotal.get());
        m.put("lagMs", ttlMs);
        m.put("runs", runs);
        m.put("lastRunMs", lastRunMs);
        m.put("lastError", lastError);
        return m;
    }
}
