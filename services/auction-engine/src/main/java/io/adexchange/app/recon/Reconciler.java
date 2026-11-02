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
 * Reconciles the budget ledger with confirmed spend.
 *
 * <p>The engine books spend when it AWARDS an impression (so it can never overspend); billing only happens when a signed impression
 * event arrives. Wins that are never rendered stay booked but never billed. This job measures that gap ("drift") and returns the
 * part that can no longer be billed to the campaign's budget.
 *
 * <p>Correction rule (conservative on purpose): tracking tokens expire after {@code lagMs}, so a booking older than that can never
 * be billed any more. Let {@code B(T)} be the spend booked up to {@code T = now - lagMs} and {@code billed} the spend billed so far
 * (which also includes newer bills, so it can only over-estimate what old bookings were billed). Then
 * {@code max(0, B(T) - billed)} is a lower bound on spend booked, expired and never billed; whatever of that has not been credited
 * before is credited back. The drift metric is the unconditional gap {@code booked - billed}, which includes in-flight impressions.
 * Single-engine semantics: with several engines each would need its own booked figure.
 */
@Component
public class Reconciler {
    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private record Point(long ts, long booked) {}

    private final LeaseManager leases;
    private final LeaseStore store;
    private final ConfirmedSpendSource source;
    private final IndexHolder index;
    private final long lagMs;

    private final Map<String, ArrayDeque<Point>> history = new HashMap<>();
    private final Map<String, Long> credited = new HashMap<>();

    private final AtomicLong driftMicros = new AtomicLong();
    private final AtomicLong creditedTotal = new AtomicLong();
    private volatile long lastRunMs;
    private volatile String lastError = "";
    private volatile long runs;

    public Reconciler(LeaseManager leases, LeaseStore store, ConfirmedSpendSource source, IndexHolder index,
                      ExchangeProperties props, MeterRegistry reg) {
        this.leases = leases;
        this.store = store;
        this.source = source;
        this.index = index;
        this.lagMs = props.reconcileLagMs();
        Gauge.builder("budget.reconciliation.drift.micros", driftMicros, AtomicLong::get).register(reg);
        Gauge.builder("budget.reconciliation.credited.micros", creditedTotal, AtomicLong::get).register(reg);
    }

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${exchange.reconcile-interval-ms:60000}")
    public void scheduled() {
        reconcile(System.currentTimeMillis());
    }

    /** One reconciliation pass at wall-clock {@code now} (separate from the scheduler so tests can drive time). */
    public synchronized void reconcile(long now) {
        Map<String, Long> billed;
        try {
            billed = source.billedByCampaign();
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            log.warn("reconciliation skipped, confirmed-spend source unavailable: {}", e.getMessage());
            return;
        }
        long drift = 0;
        long cutoff = now - lagMs;
        history.keySet().removeIf(id -> index.get(id) == null);
        for (Campaign c : index.all()) {
            String id = c.id();
            long booked = leases.totalSpent(id);
            long bill = billed.getOrDefault(id, 0L);
            drift += Math.max(0, booked - bill);

            ArrayDeque<Point> h = history.computeIfAbsent(id, k -> new ArrayDeque<>());
            h.addLast(new Point(now, booked));
            Point atCutoff = null;
            for (Point p : h) if (p.ts() <= cutoff) atCutoff = p;
            while (h.size() > 1 && h.peekFirst().ts() < (atCutoff == null ? Long.MIN_VALUE : atCutoff.ts())) h.pollFirst();
            if (atCutoff == null) continue;

            long creditable = Math.max(0, atCutoff.booked() - bill) - credited.getOrDefault(id, 0L);
            if (creditable > 0) {
                try {
                    long done = store.credit(id, creditable, now);
                    credited.merge(id, done, Long::sum);
                    creditedTotal.addAndGet(done);
                } catch (RuntimeException e) {
                    lastError = e.getMessage();
                }
            }
        }
        driftMicros.set(drift);
        lastRunMs = now;
        lastError = "";
        runs++;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source.name());
        m.put("driftMicros", driftMicros.get());
        m.put("creditedMicros", creditedTotal.get());
        m.put("lagMs", lagMs);
        m.put("runs", runs);
        m.put("lastRunMs", lastRunMs);
        m.put("lastError", lastError);
        return m;
    }
}
