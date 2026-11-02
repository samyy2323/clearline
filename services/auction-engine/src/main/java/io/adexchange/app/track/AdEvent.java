package io.adexchange.app.track;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One tracking event as it travels to Kafka and on into ClickHouse (column names match deploy/clickhouse/init.sql).
 * {@code flag} is empty for clean events and the reason (DUPLICATE, BOT_UA, CLICK_FLOOD, CLICK_WITHOUT_IMPRESSION) otherwise,
 * so flagged traffic is kept and counted instead of silently dropped, and never contributes to billed spend.
 */
public record AdEvent(
        String type,
        String id,
        @JsonProperty("ts_ms") long tsMs,
        @JsonProperty("booked_ms") long bookedMs,
        String campaign,
        String advertiser,
        String publisher,
        String geo,
        String device,
        String category,
        String arm,
        String user,
        int hour,
        @JsonProperty("price_cpm_micros") long priceCpmMicros,
        @JsonProperty("spend_micros") long spendMicros,
        double pctr,
        String flag) {

    /** {@code booked_ms} = when the spend was booked at auction time (token expiry minus token lifetime); reconciliation groups bills by it. */
    public static AdEvent of(String type, TrackingSigner.Ticket t, long spendMicros, String flag, long tokenTtlMs) {
        return new AdEvent(type, t.eventId(), System.currentTimeMillis(), t.expMs() - tokenTtlMs, t.campaign(), t.advertiser(), t.pub(), t.geo(), t.dev(),
                t.cat(), t.arm(), t.user(), t.hour(), t.priceCpmMicros(), spendMicros, t.pctr(), flag == null ? "" : flag);
    }
}
