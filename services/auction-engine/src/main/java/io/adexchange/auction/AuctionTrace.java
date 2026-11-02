package io.adexchange.auction;

import java.util.List;

/**
 * A sampled, human-readable record of one auction: who bid what, what each bidder's predicted CTR and eCPM were, and why each lost.
 * Built only for the few auctions per second the observer asks for, so the hot path pays nothing for the rest.
 */
public record AuctionTrace(
        String id,
        long tsMs,
        String arm,
        String publisher,
        String category,
        String device,
        String geo,
        List<String> segments,
        long tookMicros,
        List<Slot> slots) {

    public record Slot(String id, int w, int h, long floorCpmMicros, String winnerCampaign, long priceCpmMicros, List<Bidder> bidders) {}

    /** outcome: WON, OUTBID, BELOW_FLOOR, SIZE_MISMATCH, FREQUENCY_CAP, PACING, NO_BUDGET, WON_OTHER_SLOT */
    public record Bidder(String campaignId, String campaignName, String advertiserId, long bidCpmMicros, double pctr, double ecpm, String outcome) {}
}
