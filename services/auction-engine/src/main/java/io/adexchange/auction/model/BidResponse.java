package io.adexchange.auction.model;

import java.util.List;

public record BidResponse(
        String requestId,
        List<Bid> bids,
        String noBidReason,   // null when there is at least one bid
        boolean degraded,     // a dependency failed open (e.g. frequency cap store)
        String pricingArm) {

    /** @param priceCpmMicros clearing price; the advertiser pays {@code price/1000} per impression */
    public record Bid(String slotId, String campaignId, String advertiserId,
                      long priceCpmMicros, long bidCpmMicros, double pctr) {}

    public static BidResponse noBid(String requestId, String reason, String arm) {
        return new BidResponse(requestId, List.of(), reason, false, arm);
    }

    public boolean isNoBid() {
        return bids.isEmpty();
    }
}
