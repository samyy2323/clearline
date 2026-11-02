package io.adexchange.auction.model;

import java.util.Set;

/**
 * Campaign as seen by the hot path. Empty target sets mean "no constraint". Money in micros;
 * {@code bidCpmMicros} is the max price per 1000 impressions.
 */
public record Campaign(
        String id,
        String advertiserId,
        String name,
        long bidCpmMicros,
        long budgetMicros,
        Set<String> geos,
        Set<String> devices,
        Set<String> segments,
        Set<String> categories,
        int hourMask,          // bit h set => may serve in hour h (UTC); 0 => all hours
        Set<Size> sizes,       // creative sizes; empty => fits any slot
        int freqCap,           // impressions per user per window; 0 => uncapped
        long startMs,
        long endMs,
        boolean active) {

    public record Size(int w, int h) {}

    public boolean servesHour(int hour) {
        return hourMask == 0 || (hourMask & (1 << hour)) != 0;
    }

    public boolean fits(int w, int h) {
        return sizes.isEmpty() || sizes.contains(new Size(w, h));
    }

    public boolean inFlight(long nowMs) {
        return nowMs >= startMs && nowMs < endMs;
    }
}
