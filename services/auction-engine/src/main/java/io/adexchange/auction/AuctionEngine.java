package io.adexchange.auction;

import io.adexchange.auction.index.CampaignIndex;
import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.BidRequest;
import io.adexchange.auction.model.BidResponse;
import io.adexchange.auction.model.Campaign;
import io.adexchange.auction.pricing.PricingArms;
import io.adexchange.auction.scoring.PctrModel;
import io.adexchange.budget.LeaseManager;
import io.adexchange.clock.AdClock;
import io.adexchange.freqcap.FrequencyCapStore;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * The auction pipeline: retrieval -> scoring -> (frequency cap, pacing, budget) -> per-slot award.
 * Every stage has a time budget; a missed budget yields NO_BID, never a late bid. No network calls happen
 * here except the optional frequency-cap batch lookup (fail-open) - budget spend is purely local.
 */
public final class AuctionEngine {

    public enum Stage { RETRIEVAL, SCORING, BUDGET, TOTAL }

    public enum NoBid { NO_CANDIDATES, NO_ELIGIBLE, TIMEOUT, INVALID }

    public interface StageRecorder {
        void record(Stage stage, long nanos);
    }

    public interface PacingGate {
        boolean admit(String campaignId);
    }

    public record Budgets(long retrievalNs, long scoringNs, long budgetNs, long totalNs) {
        public static Budgets defaults() {
            return new Budgets(10_000_000L, 10_000_000L, 10_000_000L, 35_000_000L);
        }
    }

    static final int TOP_K = 32;

    private final IndexHolder index;
    private final PctrModel model;
    private final FrequencyCapStore freq;
    private final LeaseManager leases;
    private final PricingArms arms;
    private final AdClock clock;
    private final Budgets budgets;
    private volatile StageRecorder recorder = (s, n) -> {};
    private volatile PacingGate pacing = id -> true;

    public final LongAdder requests = new LongAdder();
    public final LongAdder bids = new LongAdder();
    public final LongAdder[] noBids = new LongAdder[NoBid.values().length];
    public final LongAdder degraded = new LongAdder();
    public final LongAdder budgetDenied = new LongAdder();
    public final LongAdder impressionsAwarded = new LongAdder();

    public AuctionEngine(IndexHolder index, PctrModel model, FrequencyCapStore freq, LeaseManager leases,
                         PricingArms arms, AdClock clock, Budgets budgets) {
        this.index = index;
        this.model = model;
        this.freq = freq;
        this.leases = leases;
        this.arms = arms;
        this.clock = clock;
        this.budgets = budgets;
        for (int i = 0; i < noBids.length; i++) noBids[i] = new LongAdder();
    }

    public void setRecorder(StageRecorder r) {
        this.recorder = r;
    }

    public void setPacing(PacingGate g) {
        this.pacing = g;
    }

    public PricingArms arms() {
        return arms;
    }

    private BidResponse noBid(BidRequest r, NoBid why, PricingArms.Arm arm) {
        noBids[why.ordinal()].increment();
        return BidResponse.noBid(r.id(), why.name(), arm == null ? null : arm.name);
    }

    public BidResponse bid(BidRequest req) {
        final long t0 = System.nanoTime();
        requests.increment();
        if (req == null || req.slots() == null || req.slots().isEmpty() || req.userId() == null) {
            return noBid(req == null ? new BidRequest("?", "", "", "", "", "", List.of(), List.of()) : req, NoBid.INVALID, null);
        }
        PricingArms.Arm arm = arms.select(req.id());
        arm.auctions.increment();
        final long now = clock.nowMillis();
        final int hour = clock.hourOfDay();

        // ---- stage 1: retrieval (in-memory inverted index) ----
        CampaignIndex idx = index.current();
        BitSet cand = idx.candidates(req, hour);
        long t1 = System.nanoTime();
        recorder.record(Stage.RETRIEVAL, t1 - t0);
        if (t1 - t0 > budgets.retrievalNs) return noBid(req, NoBid.TIMEOUT, arm);

        // ---- stage 2: scoring (eCPM = bid x pCTR), keep top-K ----
        int[] reqF = PctrModel.requestFeatures(req.publisherId(), req.siteCategory(), req.device(), req.geo(), hour, req.segments());
        Campaign[] top = new Campaign[TOP_K];
        double[] topPctr = new double[TOP_K], topEcpm = new double[TOP_K];
        int k = 0;
        int seen = 0;
        for (int o = cand.nextSetBit(0); o >= 0; o = cand.nextSetBit(o + 1)) {
            Campaign c = idx.campaign(o);
            if (!c.inFlight(now)) continue;
            seen++;
            double p = model.predict(reqF, PctrModel.fnv1a(c.id()));
            double e = c.bidCpmMicros() * p;
            if (k == TOP_K && e <= topEcpm[k - 1]) continue;
            int pos = Math.min(k, TOP_K - 1);
            while (pos > 0 && topEcpm[pos - 1] < e) {
                top[pos] = top[pos - 1];
                topPctr[pos] = topPctr[pos - 1];
                topEcpm[pos] = topEcpm[pos - 1];
                pos--;
            }
            top[pos] = c;
            topPctr[pos] = p;
            topEcpm[pos] = e;
            if (k < TOP_K) k++;
        }
        long t2 = System.nanoTime();
        recorder.record(Stage.SCORING, t2 - t1);
        if (seen == 0) return noBid(req, NoBid.NO_CANDIDATES, arm);
        if (t2 - t1 > budgets.scoringNs) return noBid(req, NoBid.TIMEOUT, arm);

        // ---- stage 3: frequency cap (one batch), pacing gate, award + local budget spend ----
        String[] ids = new String[k];
        int[] caps = new int[k];
        boolean[] capped = new boolean[k];
        for (int i = 0; i < k; i++) {
            ids[i] = top[i].id();
            caps[i] = top[i].freqCap();
        }
        boolean ok = freq.check(req.userId(), ids, caps, k, capped, now);
        boolean wasDegraded = !ok;
        if (wasDegraded) degraded.increment();

        boolean[] eligible = new boolean[k];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        PacingGate gate = pacing;
        for (int i = 0; i < k; i++) eligible[i] = !capped[i] && gate.admit(ids[i]);

        List<BidResponse.Bid> won = new ArrayList<>(req.slots().size());
        boolean[] used = new boolean[k];
        int[] order = new int[k];
        for (BidRequest.Slot slot : req.slots()) {
            int m = 0;
            for (int i = 0; i < k; i++) {
                if (!eligible[i] || used[i]) continue;
                Campaign c = top[i];
                if (c.bidCpmMicros() < slot.floorCpmMicros() || !c.fits(slot.w(), slot.h())) continue;
                order[m++] = i;
            }
            for (int j = 0; j < m; j++) {
                int w = order[j];
                double second = j + 1 < m ? topEcpm[order[j + 1]] : 0;
                Campaign c = top[w];
                long price = arm.strategy.clearingCpm(c.bidCpmMicros(), topPctr[w], second, slot.floorCpmMicros());
                long perImpression = Math.max(1, price / 1000);
                if (!leases.trySpend(c.id(), perImpression)) {
                    budgetDenied.increment();
                    continue; // fail closed: no lease budget -> no bid for this campaign
                }
                used[w] = true;
                won.add(new BidResponse.Bid(slot.id(), c.id(), c.advertiserId(), price, c.bidCpmMicros(), topPctr[w]));
                arm.awards.increment();
                arm.revenueMicros.add(perImpression);
                impressionsAwarded.increment();
                freq.record(req.userId(), c.id(), now);
                break;
            }
        }
        long t3 = System.nanoTime();
        recorder.record(Stage.BUDGET, t3 - t2);
        recorder.record(Stage.TOTAL, t3 - t0);
        if (t3 - t2 > budgets.budgetNs || t3 - t0 > budgets.totalNs) {
            // too late to be useful; the spend already taken stays in the lease (conservative) and is reconciled
            return noBid(req, NoBid.TIMEOUT, arm);
        }
        if (won.isEmpty()) return noBid(req, NoBid.NO_ELIGIBLE, arm);
        bids.increment();
        return new BidResponse(req.id(), won, null, wasDegraded, arm.name);
    }
}
