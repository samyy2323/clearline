package io.adexchange.app.sim;

import io.adexchange.app.bid.BidGateway;
import io.adexchange.app.campaign.CampaignService;
import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.app.config.ScalableClock;
import io.adexchange.app.fault.FaultInjector;
import io.adexchange.app.track.TrackerService;
import io.adexchange.auction.model.BidRequest;
import io.adexchange.auction.model.Campaign;
import io.adexchange.auction.scoring.PctrModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * In-process behavior simulator (lite mode drives itself with it; the standalone open-loop load generator in
 * /simulator drives the real HTTP path for benchmarks). Open loop: arrivals follow the schedule regardless of
 * how long requests take. After a win it decides view / click / duplicate / flood / bot using hidden true CTRs.
 */
@Service
public class SimulatorService {

    public record Config(String scenario, double rate, double botFraction, double dupFraction, double floodFraction,
                         int campaigns, long seed, double budgetScale) {}

    private static final int USERS = 1_000_000, PUBLISHERS = 300;
    private static final String HUMAN_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36";
    private static final String BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1)";

    private final BidGateway gateway;
    private final TrackerService tracker;
    private final CampaignService campaigns;
    private final ScalableClock clock;
    private final FaultInjector faults;
    private final ExchangeProperties props;

    private volatile boolean running;
    private volatile long runId;
    private volatile Config config;
    private volatile long runStartedAtMs;
    private Thread loop;
    private ExecutorService exec;
    private final Semaphore inFlight = new Semaphore(20_000);

    private final AtomicLong sent = new AtomicLong(), dropped = new AtomicLong(), bids = new AtomicLong(),
            humanImps = new AtomicLong(), humanClicks = new AtomicLong(), dupImpsSent = new AtomicLong(),
            dupClicksSent = new AtomicLong(), floodClicksSent = new AtomicLong(), botEventsSent = new AtomicLong(),
            clicksNoImpSent = new AtomicLong(), expectedBilledMicros = new AtomicLong();
    private final AtomicLong predSum = new AtomicLong(), trueSum = new AtomicLong(), winImps = new AtomicLong(), winClicks = new AtomicLong();
    private final Deque<double[]> learning = new ArrayDeque<>();

    public SimulatorService(BidGateway gateway, TrackerService tracker, CampaignService campaigns, ScalableClock clock,
                            FaultInjector faults, ExchangeProperties props) {
        this.gateway = gateway;
        this.tracker = tracker;
        this.campaigns = campaigns;
        this.clock = clock;
        this.faults = faults;
        this.props = props;
    }

    public static Config preset(String scenario) {
        return switch (scenario) {
            case "ramp" -> new Config("ramp", 1000, 0.02, 0.03, 0.002, 40, 42, 1);
            case "spike" -> new Config("spike", 500, 0.02, 0.03, 0.002, 40, 42, 1);
            case "soak" -> new Config("soak", 300, 0.02, 0.03, 0.002, 40, 42, 1);
            case "chaos" -> new Config("chaos", 500, 0.02, 0.03, 0.002, 40, 42, 1);
            case "compressed-day" -> new Config("compressed-day", 600, 0.02, 0.03, 0.002, 40, 42, 1);
            default -> new Config("steady", 500, 0.02, 0.03, 0.002, 40, 42, 1);
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (props.seedDemo()) {
            seedCampaigns(preset("steady"), 0);
            if (props.autostartSimRate() > 0) {
                Config c = preset("steady");
                start(new Config("steady", props.autostartSimRate(), c.botFraction(), c.dupFraction(), c.floodFraction(), c.campaigns(), c.seed(), c.budgetScale()));
            }
        }
    }

    private void seedCampaigns(Config c, long run) {
        long now = clock.nowMillis();
        long start, end;
        if (c.scenario().equals("compressed-day")) {
            start = now - now % 86_400_000L;
            end = start + 86_400_000L;
        } else {
            start = now - 5 * 60_000L;
            end = now + 60 * 60_000L;
        }
        campaigns.replaceAll(DemoData.generate(c.seed(), c.campaigns(), start, end, c.budgetScale(), "c" + run + "-"));
    }

    public synchronized Map<String, Object> start(Config c) {
        stop();
        long id = ++runId;
        tracker.reset();
        for (AtomicLong a : List.of(sent, dropped, bids, humanImps, humanClicks, dupImpsSent, dupClicksSent, floodClicksSent,
                botEventsSent, clicksNoImpSent, expectedBilledMicros, predSum, trueSum, winImps, winClicks)) a.set(0);
        synchronized (learning) {
            learning.clear();
        }
        faults.redisDown.set(false);
        faults.extraLatencyMs.set(0);
        if (c.scenario().equals("compressed-day")) {
            long now = System.currentTimeMillis();
            clock.set(72.0, now - now % 86_400_000L); // 24h in 20 min
        } else {
            clock.set(1.0, System.currentTimeMillis());
        }
        seedCampaigns(c, id);
        config = c;
        running = true;
        runStartedAtMs = System.currentTimeMillis();
        exec = Executors.newVirtualThreadPerTaskExecutor();
        loop = Thread.ofPlatform().name("sim-loop").daemon().start(() -> run(c, id));
        return status();
    }

    public synchronized void stop() {
        running = false;
        if (loop != null) loop.interrupt();
        if (exec != null) exec.shutdown();
    }

    private double rateAt(Config c, double elapsedSec) {
        return switch (c.scenario()) {
            case "ramp" -> c.rate() * Math.min(1.0, 0.1 + elapsedSec / 60.0);
            case "spike" -> c.rate() * ((elapsedSec % 40) >= 20 && (elapsedSec % 40) < 30 ? 3.0 : 1.0);
            case "compressed-day" -> {
                double h = (clock.nowMillis() % 86_400_000L) / 3_600_000.0;
                double diurnal = 0.55 + 0.45 * Math.sin((h - 9) / 24.0 * 2 * Math.PI);
                boolean burst = (h % 6.0) < 0.17; // ~10 simulated minutes every 6h at 3x
                yield c.rate() * diurnal * (burst ? 3.0 : 1.0);
            }
            default -> c.rate();
        };
    }

    private void run(Config c, long id) {
        long startNs = System.nanoTime(), lastNs = startNs;
        double due = 0;
        long n = 0;
        while (running && runId == id) {
            long now = System.nanoTime();
            double elapsed = (now - startNs) / 1e9;
            due += rateAt(c, elapsed) * (now - lastNs) / 1e9;
            lastNs = now;
            if (c.scenario().equals("chaos")) {
                faults.redisDown.set(elapsed >= 30 && elapsed < 50);
                faults.extraLatencyMs.set(elapsed >= 70 && elapsed < 90 ? 200 : 0);
            }
            int k = (int) due;
            due -= k;
            for (int i = 0; i < k; i++) {
                final long seq = n++;
                if (!inFlight.tryAcquire()) {
                    dropped.incrementAndGet();
                    continue;
                }
                try {
                    exec.submit(() -> {
                        try {
                            oneRequest(c, seq);
                        } catch (RuntimeException e) {
                            // a simulator error must never take down the loop
                        } finally {
                            inFlight.release();
                        }
                    });
                } catch (RuntimeException rejected) {
                    inFlight.release();
                    return;
                }
            }
            LockSupport.parkNanos(500_000);
        }
    }

    private static String seg(int i) {
        return DemoData.SEGMENTS[Math.floorMod(i, DemoData.SEGMENTS.length)];
    }

    private void oneRequest(Config c, long seq) {
        SplittableRandom r = new SplittableRandom(c.seed() * 0x9E3779B97F4A7C15L + seq);
        sent.incrementAndGet();
        boolean flooder = r.nextDouble() < c.floodFraction();
        int u = (int) Math.min(USERS - 1, Math.pow(USERS, r.nextDouble())); // log-uniform ~ Zipf(s=1) user popularity
        int h = PctrModel.fmix(u * 0x9E3779B1 + 1);
        int w = (h & 0xff) % 100, acc = 0, gi = 0;
        for (; gi < DemoData.GEOS.length; gi++) {
            acc += DemoData.GEO_WEIGHTS[gi];
            if (w < acc) break;
        }
        gi = Math.min(gi, DemoData.GEOS.length - 1);
        String geo = DemoData.GEOS[gi];
        int dv = ((h >>> 8) & 0xff) % 100;
        String device = (geo.equals("IN") || geo.equals("ID") || geo.equals("BR")) ? (dv < 80 ? "mobile" : dv < 95 ? "desktop" : "tablet")
                : (dv < 45 ? "mobile" : dv < 85 ? "desktop" : dv < 95 ? "ctv" : "tablet");
        int nseg = 1 + ((h >>> 16) & 3) % 3;
        List<String> segs = new ArrayList<>(nseg);
        for (int k = 0; k < nseg; k++) segs.add(seg(gi * 3 + k * 7 + (h >>> 18) % 5));
        int pub = (int) Math.min(PUBLISHERS - 1, Math.pow(PUBLISHERS, r.nextDouble()));
        int cat = pub % DemoData.CATEGORIES.length;
        long floor = 100_000L + (PctrModel.fmix(pub) & 0x7) * 400_000L;
        int nslots = 1 + (pub % 3 == 0 ? 1 : 0);
        List<BidRequest.Slot> slots = new ArrayList<>(nslots);
        for (int i = 0; i < nslots; i++) {
            String[] sz = DemoData.SIZES[(pub + i) % DemoData.SIZES.length];
            slots.add(new BidRequest.Slot("s" + i, Integer.parseInt(sz[0]), Integer.parseInt(sz[1]), floor));
        }
        String user = flooder ? "flood-" + (seq % 3) : "u" + u;
        BidRequest req = new BidRequest("r" + runId + "-" + seq, "pub" + pub, DemoData.CATEGORIES[cat], device, geo, user, segs, slots);

        BidGateway.Result res = gateway.handle(req);
        if (res.noBid()) return;
        bids.incrementAndGet();
        int hour = clock.hourOfDay();
        int seg0 = Math.floorMod(gi * 3 + (h >>> 18) % 5, DemoData.SEGMENTS.length);
        double trueCtr = DemoData.trueCtr(seg0, cat, hour);
        String ip = "10." + (u & 0xff) + "." + ((u >>> 8) & 0xff) + "." + ((u >>> 16) & 0xff);
        for (BidGateway.Offer o : res.seatbid()) {
            boolean bot = r.nextDouble() < c.botFraction();
            String ua = bot ? BOT_UA : HUMAN_UA;
            String[] imp = split(o.impUrl()), click = split(o.clickUrl()), win = split(o.winUrl());
            tracker.handle("win", win[0], win[1], ip, ua);
            if (r.nextDouble() < 0.003) { // click with no delivered impression
                clicksNoImpSent.incrementAndGet();
                tracker.handle("click", click[0], click[1], ip, ua);
                continue;
            }
            if (r.nextDouble() >= 0.9) continue; // never viewed
            tracker.handle("imp", imp[0], imp[1], ip, ua);
            if (bot) {
                botEventsSent.incrementAndGet();
                tracker.handle("click", click[0], click[1], ip, ua);
                continue;
            }
            expectedBilledMicros.addAndGet(Math.max(1, o.priceCpmMicros() / 1000));
            humanImps.incrementAndGet();
            winImps.incrementAndGet();
            predSum.addAndGet((long) (o.pctr() * 1_000_000));
            trueSum.addAndGet((long) (trueCtr * 1_000_000));
            if (r.nextDouble() < c.dupFraction()) {
                dupImpsSent.incrementAndGet();
                tracker.handle("imp", imp[0], imp[1], ip, ua);
            }
            if (flooder || r.nextDouble() < trueCtr) {
                humanClicks.incrementAndGet();
                winClicks.incrementAndGet();
                tracker.handle("click", click[0], click[1], ip, ua);
                if (r.nextDouble() < c.dupFraction()) {
                    dupClicksSent.incrementAndGet();
                    tracker.handle("click", click[0], click[1], ip, ua);
                }
                if (flooder) floodClicksSent.incrementAndGet();
            }
        }
    }

    private static String[] split(String url) {
        int d = url.indexOf("d=") + 2, s = url.indexOf("&s=");
        return new String[]{url.substring(d, s), url.substring(s + 3)};
    }

    /** Every 5 s: predicted vs true vs observed CTR over the window (the "learning loop" chart). */
    @Scheduled(fixedRate = 5000)
    void learningBin() {
        long imps = winImps.getAndSet(0), clicks = winClicks.getAndSet(0);
        long pred = predSum.getAndSet(0), tru = trueSum.getAndSet(0);
        if (imps < 50) return;
        synchronized (learning) {
            if (learning.size() >= 360) learning.pollFirst();
            learning.addLast(new double[]{System.currentTimeMillis(), pred / 1e6 / imps, tru / 1e6 / imps, (double) clicks / imps});
        }
    }

    @Scheduled(fixedRate = 10_000)
    void reportNothing() {} // keeps scheduler thread warm; placeholder for future periodic sim tasks

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Config c = config;
        m.put("running", running);
        m.put("config", c);
        m.put("elapsedSec", running ? (System.currentTimeMillis() - runStartedAtMs) / 1000 : 0);
        m.put("sent", sent.get());
        m.put("dropped", dropped.get());
        m.put("bids", bids.get());
        m.put("humanImpressions", humanImps.get());
        m.put("humanClicks", humanClicks.get());
        m.put("injected", Map.of("duplicateImpressions", dupImpsSent.get(), "duplicateClicks", dupClicksSent.get(),
                "floodClicks", floodClicksSent.get(), "botEvents", botEventsSent.get(), "clickWithoutImpression", clicksNoImpSent.get()));
        long expected = expectedBilledMicros.get(), actual = tracker.ledger().total();
        m.put("billing", Map.of("expectedMicros", expected, "actualMicros", actual, "driftMicros", actual - expected));
        synchronized (learning) {
            m.put("learning", new ArrayList<>(learning));
        }
        return m;
    }
}
