package io.adexchange.app.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adexchange.app.bid.BidGateway;
import io.adexchange.app.campaign.CampaignService;
import io.adexchange.app.config.PacingRegistry;
import io.adexchange.app.config.ScalableClock;
import io.adexchange.app.fault.FaultInjector;
import io.adexchange.app.sim.SimulatorService;
import io.adexchange.app.kafka.CampaignEventConsumer;
import io.adexchange.app.recon.Reconciler;
import io.adexchange.app.track.AdEventSink;
import io.adexchange.app.track.EventLog;
import io.adexchange.app.track.EventStats;
import io.adexchange.app.track.TrackerService;
import io.adexchange.auction.AuctionEngine;
import io.adexchange.auction.model.Campaign;
import io.adexchange.auction.scoring.PctrModel;
import io.adexchange.budget.LeaseManager;
import io.adexchange.budget.LeaseStore;
import io.adexchange.budget.PacingController;
import io.adexchange.freqcap.ResilientFrequencyCap;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 1 Hz sampler: builds the live snapshot used by the dashboard (SSE), keeps a rolling window for charts and
 * registers Micrometer meters for Prometheus. Percentiles come from HdrHistogram interval snapshots.
 */
@Service
public class StatsService {

    public record Subscriber(SseEmitter emitter, boolean admin, String advertiserId) {}

    private final AuctionEngine engine;
    private final BidGateway gateway;
    private final CampaignService campaigns;
    private final EventStats stats;
    private final TrackerService tracker;
    private final LeaseManager leases;
    private final LeaseStore store;
    private final PacingRegistry pacing;
    private final ScalableClock clock;
    private final ResilientFrequencyCap freq;
    private final PctrModel model;
    private final FaultInjector faults;
    private final EventLog eventLog;
    private final SimulatorService sim;
    private final AdEventSink sink;
    private final Reconciler reconciler;
    private final org.springframework.beans.factory.ObjectProvider<CampaignEventConsumer> campaignConsumer;
    private final ObjectMapper json = new ObjectMapper();

    private final Recorder[] stageRec = new Recorder[AuctionEngine.Stage.values().length];
    private final Histogram[] stageCum = new Histogram[stageRec.length];
    private final List<Subscriber> subs = new CopyOnWriteArrayList<>();
    private final Deque<Map<String, Object>> window = new ArrayDeque<>();
    private final Map<String, Deque<double[]>> pacingSeries = new ConcurrentHashMap<>();

    private volatile Map<String, Object> latest = Map.of();
    private long lastReq, lastBids, lastShed, lastTs = System.currentTimeMillis();
    private long lastNoBidCount, lastTimeouts;
    private int tickNo;

    public StatsService(AuctionEngine engine, BidGateway gateway, CampaignService campaigns, EventStats stats,
                        TrackerService tracker, LeaseManager leases, LeaseStore store, PacingRegistry pacing,
                        ScalableClock clock, ResilientFrequencyCap freq, PctrModel model, FaultInjector faults,
                        EventLog eventLog, SimulatorService sim, AdEventSink sink, Reconciler reconciler,
                        org.springframework.beans.factory.ObjectProvider<CampaignEventConsumer> campaignConsumer, MeterRegistry reg) {
        this.engine = engine; this.gateway = gateway; this.campaigns = campaigns; this.stats = stats;
        this.tracker = tracker; this.leases = leases; this.store = store; this.pacing = pacing; this.clock = clock;
        this.freq = freq; this.model = model; this.faults = faults; this.eventLog = eventLog; this.sim = sim; this.sink = sink; this.reconciler = reconciler; this.campaignConsumer = campaignConsumer;

        Timer[] timers = new Timer[stageRec.length];
        for (AuctionEngine.Stage s : AuctionEngine.Stage.values()) {
            int i = s.ordinal();
            stageRec[i] = new Recorder(1, 60_000_000_000L, 3);
            stageCum[i] = new Histogram(1, 60_000_000_000L, 3);
            timers[i] = Timer.builder("auction.stage.latency").tag("stage", s.name().toLowerCase())
                    .publishPercentileHistogram().register(reg);
        }
        engine.setRecorder((stage, nanos) -> {
            stageRec[stage.ordinal()].recordValue(Math.max(1, nanos));
            timers[stage.ordinal()].record(nanos, TimeUnit.NANOSECONDS);
        });
        FunctionCounter.builder("auction.requests", engine.requests, LongAdder::sum).register(reg);
        FunctionCounter.builder("auction.bids", engine.bids, LongAdder::sum).register(reg);
        FunctionCounter.builder("gateway.shed", gateway.shed, LongAdder::sum).register(reg);
        FunctionCounter.builder("budget.spend.denied", leases.spendDenied, a -> a.get()).register(reg);
        FunctionCounter.builder("budget.renew.failures", leases.renewFailures, a -> a.get()).register(reg);
        FunctionCounter.builder("freqcap.degraded", freq.degradedChecks, a -> a.get()).register(reg);
        Gauge.builder("gateway.inflight", gateway, BidGateway::inFlight).register(reg);
        Gauge.builder("billing.total.micros", tracker, t -> t.ledger().total()).register(reg);
        Gauge.builder("budget.overspend.pct", this, StatsService::overspendPct).register(reg);
    }

    public double overspendPct() {
        long budget = 0, over = 0;
        for (Campaign c : campaigns.all()) {
            budget += c.budgetMicros();
            over += Math.max(0, leases.totalSpent(c.id()) - c.budgetMicros());
        }
        return budget == 0 ? 0 : 100.0 * over / budget;
    }

    public SseEmitter subscribe(boolean admin, String adv) {
        SseEmitter e = new SseEmitter(0L);
        Subscriber s = new Subscriber(e, admin, adv);
        subs.add(s);
        e.onCompletion(() -> subs.remove(s));
        e.onTimeout(() -> subs.remove(s));
        e.onError(t -> subs.remove(s));
        try {
            e.send(SseEmitter.event().name("snapshot").data(json.writeValueAsString(filter(latest, s))));
        } catch (IOException | RuntimeException ex) {
            subs.remove(s);
        }
        return e;
    }

    public Map<String, Object> latest(boolean admin, String adv) {
        return filter(latest, new Subscriber(null, admin, adv));
    }

    public List<Map<String, Object>> history() {
        synchronized (window) {
            return new ArrayList<>(window);
        }
    }

    public List<double[]> pacingSeries(String campaignId) {
        Deque<double[]> d = pacingSeries.get(campaignId);
        if (d == null) return List.of();
        synchronized (d) {
            return new ArrayList<>(d);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filter(Map<String, Object> snap, Subscriber s) {
        if (s.admin() || snap.isEmpty()) return snap;
        Map<String, Object> m = new LinkedHashMap<>(snap);
        List<Map<String, Object>> all = (List<Map<String, Object>>) snap.get("campaigns");
        m.put("campaigns", all.stream().filter(c -> s.advertiserId().equals(c.get("advertiserId"))).toList());
        m.remove("fraud");
        m.remove("sim");
        m.remove("system");
        return m;
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    @Scheduled(fixedRate = 1000)
    void sample() {
        long now = System.currentTimeMillis();
        double dt = Math.max(0.001, (now - lastTs) / 1000.0);
        lastTs = now;

        long req = engine.requests.sum(), bids = engine.bids.sum(), shed = gateway.shed.sum();
        long timeouts = engine.noBids[AuctionEngine.NoBid.TIMEOUT.ordinal()].sum();
        long dReq = req - lastReq, dBids = bids - lastBids, dShed = shed - lastShed, dTimeout = timeouts - lastTimeouts;
        lastReq = req; lastBids = bids; lastShed = shed; lastTimeouts = timeouts;

        Histogram intervalHist = gateway.latency.getIntervalHistogram();
        Map<String, Object> lat = new LinkedHashMap<>();
        lat.put("p50", ms(intervalHist.getValueAtPercentile(50)));
        lat.put("p95", ms(intervalHist.getValueAtPercentile(95)));
        lat.put("p99", ms(intervalHist.getValueAtPercentile(99)));
        lat.put("max", ms(intervalHist.getMaxValue()));
        Map<String, Object> stages = new LinkedHashMap<>();
        for (AuctionEngine.Stage st : AuctionEngine.Stage.values()) {
            Histogram h = stageRec[st.ordinal()].getIntervalHistogram();
            stages.put(st.name().toLowerCase(), Map.of("p50", ms(h.getValueAtPercentile(50)), "p99", ms(h.getValueAtPercentile(99))));
        }

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("t", now);
        snap.put("simTime", clock.nowMillis());
        snap.put("simSpeed", clock.speed());
        snap.put("rps", Math.round(dReq / dt));
        snap.put("bidRate", dReq == 0 ? 0 : (double) dBids / dReq);
        snap.put("noBidRate", dReq == 0 ? 0 : 1.0 - (double) dBids / dReq);
        snap.put("errorRate", dReq == 0 ? 0 : (double) (dShed + dTimeout) / (dReq + dShed));
        snap.put("latencyMs", lat);
        snap.put("stagesMs", stages);

        long totalBudget = 0, totalLeaseSpent = 0;
        List<Map<String, Object>> camps = new ArrayList<>();
        boolean sampleSeries = (++tickNo % 2) == 0;
        for (Campaign c : campaigns.all()) {
            EventStats.Counters k = stats.of(c.id());
            long spent = leases.totalSpent(c.id());
            totalBudget += c.budgetMicros();
            totalLeaseSpent += spent;
            PacingController pc = pacing.get(c.id());
            double target = pc == null ? 0 : pc.targetFraction();
            double actual = c.budgetMicros() == 0 ? 0 : (double) spent / c.budgetMicros();
            long imps = k.imps.sum(), clicks = k.clicks.sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("advertiserId", c.advertiserId());
            m.put("budgetMicros", c.budgetMicros());
            m.put("spentMicros", spent);
            m.put("billedMicros", tracker.ledger().billed(c.id()));
            m.put("awards", k.awards.sum());
            m.put("impressions", imps);
            m.put("clicks", clicks);
            m.put("ctr", imps == 0 ? 0 : (double) clicks / imps);
            m.put("predCtr", imps == 0 ? 0 : k.predCtrMicros.sum() / 1e6 / imps);
            m.put("winRate", 0); // filled below from awards/auctions is not tracked per campaign; see arms
            m.put("targetFraction", target);
            m.put("actualFraction", actual);
            m.put("participation", pc == null ? 1 : pc.participation());
            camps.add(m);
            if (sampleSeries) {
                Deque<double[]> d = pacingSeries.computeIfAbsent(c.id(), x -> new ArrayDeque<>());
                synchronized (d) {
                    if (d.size() >= 600) d.pollFirst();
                    d.addLast(new double[]{clock.nowMillis(), target, actual});
                }
            }
        }
        camps.forEach(m -> m.remove("winRate"));
        snap.put("campaigns", camps);
        pacingSeries.keySet().removeIf(id -> campaigns.get(id) == null);

        double overPct = totalBudget == 0 ? 0 : 100.0 * camps.stream()
                .mapToDouble(m -> Math.max(0, (long) m.get("spentMicros") - (long) m.get("budgetMicros"))).sum() / totalBudget;
        snap.put("overspendPct", overPct);
        snap.put("totalBudgetMicros", totalBudget);
        snap.put("totalSpentMicros", totalLeaseSpent);
        snap.put("totalBilledMicros", tracker.ledger().total());

        List<Map<String, Object>> arms = new ArrayList<>();
        for (var a : engine.arms().arms()) {
            arms.add(Map.of("name", a.name, "auctions", a.auctions.sum(), "awards", a.awards.sum(),
                    "awardedRevenueMicros", a.revenueMicros.sum(), "billedRevenueMicros", tracker.ledger().billedByArm(a.name),
                    "revenuePerAuctionMicros", a.auctions.sum() == 0 ? 0 : (double) a.revenueMicros.sum() / a.auctions.sum()));
        }
        snap.put("arms", arms);

        snap.put("leases", Map.of(
                "acquired", leases.leasesAcquired.get(), "spendApproved", leases.spendApproved.get(),
                "spendDenied", leases.spendDenied.get(), "renewFailures", leases.renewFailures.get(),
                "budgetDenied", engine.budgetDenied.sum(), "chunkMicros", 0));
        snap.put("events", sink.stats());
        snap.put("reconciliation", reconciler.status());
        CampaignEventConsumer cc = campaignConsumer.getIfAvailable();
        snap.put("campaignFeed", cc == null ? Map.of("mode", "in-process outbox")
                : Map.of("mode", "kafka compacted topic", "caughtUp", cc.caughtUp(), "applied", cc.applied.get(), "skipped", cc.skipped.get()));
        snap.put("kafkaLag", null);
        snap.put("model", Map.of("version", model.version(), "source", model.source()));
        snap.put("fraud", Map.of("flagged", toMap(stats.flagged), "rejected", toMap(stats.rejected), "recent", stats.recentFlags().size()));
        snap.put("system", Map.of(
                "inFlight", gateway.inFlight(), "shed", shed, "degradedFreqCap", freq.degradedChecks.get(),
                "freqDroppedWrites", freq.droppedWrites.get(), "outboxDepth", campaigns.outboxDepth(),
                "indexVersion", 0, "eventLogDropped", eventLog.dropped.get(), "faults", faults.state()));
        snap.put("sim", sim.status());

        latest = snap;
        synchronized (window) {
            if (window.size() >= 900) window.pollFirst();
            window.addLast(globalOnly(snap));
        }
        for (Subscriber s : subs) {
            try {
                s.emitter().send(SseEmitter.event().name("snapshot").data(json.writeValueAsString(filter(snap, s))));
            } catch (IOException | RuntimeException e) {
                subs.remove(s);
            }
        }
    }

    private static Map<String, Object> globalOnly(Map<String, Object> snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("t", "simTime", "rps", "bidRate", "noBidRate", "errorRate", "latencyMs", "overspendPct", "arms"))
            m.put(k, snap.get(k));
        return m;
    }

    private static Map<String, Long> toMap(Map<String, LongAdder> src) {
        Map<String, Long> m = new TreeMap<>();
        src.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }
}
