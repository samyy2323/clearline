package io.adexchange.app.config;

import io.adexchange.app.fault.FaultInjector;
import io.adexchange.auction.AuctionEngine;
import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.pricing.PricingArms;
import io.adexchange.auction.pricing.PricingStrategy;
import io.adexchange.auction.scoring.PctrModel;
import io.adexchange.budget.InMemoryLeaseStore;
import io.adexchange.budget.LeaseManager;
import io.adexchange.budget.LeaseStore;
import io.adexchange.budget.RedisLeaseStore;
import io.adexchange.clock.AdClock;
import io.adexchange.freqcap.FrequencyCapStore;
import io.adexchange.freqcap.TieredFrequencyCap;
import io.adexchange.freqcap.InMemoryFrequencyCapStore;
import io.adexchange.freqcap.RedisFrequencyCapStore;
import io.adexchange.freqcap.ResilientFrequencyCap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.UUID;

@Configuration
public class ExchangeConfig {
    private static final Logger log = LoggerFactory.getLogger(ExchangeConfig.class);

    /** Jackson's default thread-local buffer pool is useless under virtual threads (fresh 8-16 KB buffers per request). */
    @Bean
    org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer jacksonRecyclerPool() {
        return b -> b.factory(com.fasterxml.jackson.core.JsonFactory.builder()
                .recyclerPool(com.fasterxml.jackson.core.util.JsonRecyclerPools.sharedLockFreePool()).build());
    }

    @Bean
    ScalableClock simClock() {
        return new ScalableClock();
    }

    @Bean
    FaultInjector faults() {
        return new FaultInjector();
    }

    @Bean
    LeaseStore leaseStore(ExchangeProperties p, FaultInjector faults) {
        if (p.jwtSecret().startsWith("dev-") || p.hmacSecret().startsWith("dev-"))
            log.warn("Using development secrets - set JWT_SECRET and HMAC_SECRET for any real deployment");
        boolean redis = p.redisUri() != null && !p.redisUri().isBlank();
        log.info("Budget ledger: {}", redis ? "Redis (Lua leases)" : "in-memory (lite mode)");
        return faults.wrap(redis ? new RedisLeaseStore(p.redisUri()) : new InMemoryLeaseStore());
    }

    @Bean(destroyMethod = "close")
    ResilientFrequencyCap frequencyCap(ExchangeProperties p, FaultInjector faults) {
        long day = 86_400_000L, hour = 3_600_000L;
        boolean redis = p.redisUri() != null && !p.redisUri().isBlank();
        FrequencyCapStore base = redis ? new RedisFrequencyCapStore(p.redisUri(), day, hour) : new InMemoryFrequencyCapStore(day, hour);
        return new ResilientFrequencyCap(faults.wrap(base), 50_000);
    }

    /**
     * Optional count-min tier in front of the frequency-cap store (FREQ_LOCAL_TIER=true): candidates provably below their cap are answered locally,
     * so Redis only sees the ones near or over it. Primary, so the engine uses the tier when enabled and the plain resilient store otherwise.
     */
    @Bean
    @Primary
    FrequencyCapStore frequencyTier(ResilientFrequencyCap resilient, @Value("${exchange.freq-local-tier:false}") boolean enabled) {
        return enabled ? new TieredFrequencyCap(resilient, 4, 1 << 16, 24, 3_600_000L) : resilient;
    }

    /** Leases are an operational (real-time) concept, so they use wall time even when the sim clock is compressed. */
    @Bean(destroyMethod = "shutdown")
    LeaseManager leaseManager(LeaseStore store, ExchangeProperties p) {
        String id = "engine-" + UUID.randomUUID().toString().substring(0, 8);
        long skew = Math.max(500, p.leaseTtlMs() / 10);
        return new LeaseManager(store, AdClock.system(), id, p.leaseChunkMicros(), p.leaseTtlMs(), skew, 0.4);
    }

    @Bean
    IndexHolder indexHolder() {
        return new IndexHolder();
    }

    @Bean
    PctrModel pctrModel() {
        return new PctrModel();
    }

    @Bean
    PricingArms pricingArms(ExchangeProperties p) {
        return switch (p.pricing()) {
            case "first" -> PricingArms.single(PricingStrategy.FIRST_PRICE);
            case "gsp" -> PricingArms.single(PricingStrategy.GSP);
            default -> new PricingArms(List.of(PricingStrategy.FIRST_PRICE, PricingStrategy.GSP), List.of(50, 50));
        };
    }

    @Bean
    AuctionEngine auctionEngine(IndexHolder index, PctrModel model, FrequencyCapStore freq, LeaseManager leases,
                                PricingArms arms, ScalableClock clock) {
        return new AuctionEngine(index, model, freq, leases, arms, clock, AuctionEngine.Budgets.defaults());
    }
}
