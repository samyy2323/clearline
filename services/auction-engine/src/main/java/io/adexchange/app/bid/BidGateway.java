package io.adexchange.app.bid;

import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.app.config.ScalableClock;
import io.adexchange.app.track.EventStats;
import io.adexchange.app.track.TrackerService;
import io.adexchange.app.track.TrackingSigner;
import io.adexchange.auction.AuctionEngine;
import io.adexchange.auction.model.BidRequest;
import io.adexchange.auction.model.BidResponse;
import org.HdrHistogram.Recorder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Gateway logic shared by the HTTP endpoint and the in-process simulator: load shedding, engine call,
 * signed tracking URL construction, latency recording.
 */
@Service
public class BidGateway {

    public record Offer(String slotId, String campaignId, String advertiserId, long priceCpmMicros, double pctr,
                        String winUrl, String impUrl, String clickUrl) {}

    public record Result(String id, List<Offer> seatbid, String nbr, boolean degraded, String arm) {
        public boolean noBid() {
            return seatbid.isEmpty();
        }
    }

    private final AuctionEngine engine;
    private final TrackerService tracker;
    private final EventStats stats;
    private final ScalableClock clock;
    private final int maxInFlight;
    private final long tokenTtlMs;
    private final AtomicInteger inFlight = new AtomicInteger();

    public final LongAdder shed = new LongAdder();
    public final LongAdder handled = new LongAdder();
    public final LongAdder invalid = new LongAdder();
    /** Gateway-observed latency (engine + URL signing), nanoseconds. */
    public final Recorder latency = new Recorder(1, 60_000_000_000L, 3);

    public BidGateway(AuctionEngine engine, TrackerService tracker, EventStats stats, ScalableClock clock, ExchangeProperties props) {
        this.engine = engine;
        this.tracker = tracker;
        this.stats = stats;
        this.clock = clock;
        this.maxInFlight = props.maxInFlight();
        this.tokenTtlMs = props.tokenTtlMs();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public Result handle(BidRequest req) {
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet();
            shed.increment();
            return new Result(req.id(), List.of(), "SHED", false, null);
        }
        long t0 = System.nanoTime();
        try {
            handled.increment();
            BidResponse r = engine.bid(req);
            if (r.isNoBid()) return new Result(r.requestId(), List.of(), r.noBidReason(), r.degraded(), r.pricingArm());
            int hour = clock.hourOfDay();
            String segs = String.join(",", req.segments());
            long exp = System.currentTimeMillis() + tokenTtlMs;
            List<Offer> offers = new ArrayList<>(r.bids().size());
            for (BidResponse.Bid b : r.bids()) {
                var t = new TrackingSigner.Ticket(req.id(), b.slotId(), b.campaignId(), b.advertiserId(), b.priceCpmMicros(),
                        r.pricingArm(), req.userId(), req.publisherId(), req.siteCategory(), req.device(), req.geo(), hour, segs, b.pctr(), exp);
                String[] q = tracker.signer().signAll(t);
                offers.add(new Offer(b.slotId(), b.campaignId(), b.advertiserId(), b.priceCpmMicros(), b.pctr(),
                        "/track/win?" + q[0], "/track/imp?" + q[1], "/track/click?" + q[2]));
                var c = stats.of(b.campaignId());
                c.awards.increment();
                c.awardedMicros.add(Math.max(1, b.priceCpmMicros() / 1000));
            }
            return new Result(r.requestId(), offers, null, r.degraded(), r.pricingArm());
        } finally {
            inFlight.decrementAndGet();
            latency.recordValue(Math.max(1, System.nanoTime() - t0));
        }
    }
}
