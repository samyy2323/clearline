package io.adexchange.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All knobs come from env/application.yml; secrets have dev defaults that are logged as a warning at startup. */
@ConfigurationProperties(prefix = "exchange")
public record ExchangeProperties(
        String pricing,           // first | gsp | ab
        long leaseChunkMicros,
        long leaseTtlMs,
        String redisUri,          // blank => in-memory stores (lite mode)
        String hmacSecret,
        String jwtSecret,
        String adminPassword,
        String advertiserPassword,
        int maxInFlight,
        int ipRateLimitPerSec,    // 0 = off
        String modelPath,
        String eventsLog,
        boolean seedDemo,
        double autostartSimRate,  // >0: lite mode drives itself with simulated traffic at this rate
        boolean pacingEnabled) {

    public ExchangeProperties {
        if (pricing == null) pricing = "ab";
        if (leaseChunkMicros <= 0) leaseChunkMicros = 5_000_000;
        if (leaseTtlMs <= 0) leaseTtlMs = 60_000;
        if (hmacSecret == null || hmacSecret.isBlank()) hmacSecret = "dev-hmac-secret-change-me";
        if (jwtSecret == null || jwtSecret.isBlank()) jwtSecret = "dev-jwt-secret-change-me-dev-jwt-secret";
        if (adminPassword == null || adminPassword.isBlank()) adminPassword = "admin123";
        if (advertiserPassword == null || advertiserPassword.isBlank()) advertiserPassword = "advertiser123";
        if (maxInFlight <= 0) maxInFlight = 4000;
        if (modelPath == null) modelPath = "ml/out/weights.json";
        if (eventsLog == null) eventsLog = "data/events.jsonl";
    }
}
