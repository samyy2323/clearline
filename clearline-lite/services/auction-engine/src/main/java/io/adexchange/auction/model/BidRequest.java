package io.adexchange.auction.model;

import java.util.List;

/** OpenRTB-lite request. */
public record BidRequest(
        String id,
        String publisherId,
        String siteCategory,
        String device,
        String geo,
        String userId,
        List<String> segments,
        List<Slot> slots) {

    public record Slot(String id, int w, int h, long floorCpmMicros) {}
}
