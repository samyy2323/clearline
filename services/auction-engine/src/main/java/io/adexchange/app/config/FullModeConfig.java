package io.adexchange.app.config;

import io.adexchange.app.kafka.CampaignEventConsumer;
import io.adexchange.auction.index.IndexHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans that exist only in the full edition (Kafka-fed index). */
@Configuration
@ConditionalOnProperty(name = "exchange.mode", havingValue = "full")
class FullModeConfig {

    @Bean(destroyMethod = "close")
    CampaignEventConsumer campaignEventConsumer(ExchangeProperties p, IndexHolder index) {
        return new CampaignEventConsumer(p.kafkaBootstrap(), index);
    }
}
