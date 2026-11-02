package io.adexchange.app.recon;

import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.app.track.TrackerService;
import io.adexchange.clickhouse.ClickHouseClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Configuration
class ConfirmedSpendSources {

    /** Lite: the in-memory billing ledger. */
    @Bean
    @ConditionalOnProperty(name = "exchange.mode", havingValue = "lite", matchIfMissing = true)
    ConfirmedSpendSource ledgerSource(TrackerService tracker) {
        return new ConfirmedSpendSource() {
            public Map<String, Long> billedByCampaign() {
                Map<String, Long> m = new HashMap<>();
                for (Map.Entry<String, LongAdder> e : tracker.ledger().byCampaign().entrySet()) m.put(e.getKey(), e.getValue().sum());
                return m;
            }

            public Map<String, Long> billedForBookingsUpTo(long tMs) {
                return tracker.ledger().billedForBookingsUpTo(tMs);
            }

            public String name() {
                return "billing ledger (in memory)";
            }
        };
    }

    /** Full: what actually arrived in ClickHouse through Kafka, i.e. billed spend as the analytics store sees it. */
    @Bean
    @ConditionalOnProperty(name = "exchange.mode", havingValue = "full")
    ClickHouseClient clickHouseClient(ExchangeProperties p) {
        return new ClickHouseClient(p.clickhouseUrl(), p.clickhouseUser(), p.clickhousePassword());
    }

    @Bean
    @ConditionalOnProperty(name = "exchange.mode", havingValue = "full")
    ConfirmedSpendSource clickHouseSource(ClickHouseClient ch) {
        return new ConfirmedSpendSource() {
            public Map<String, Long> billedByCampaign() {
                Map<String, Long> m = new HashMap<>();
                ch.query("SELECT campaign, sum(spend_micros) AS spend FROM clearline.ad_events WHERE type = 'imp' AND flag = '' GROUP BY campaign")
                        .forEach(r -> m.put(r.get("campaign").asText(), ClickHouseClient.num(r, "spend")));
                return m;
            }

            public Map<String, Long> billedForBookingsUpTo(long tMs) {
                Map<String, Long> m = new HashMap<>();
                ch.query("SELECT campaign, sum(spend_micros) AS spend FROM clearline.ad_events "
                                + "WHERE type = 'imp' AND flag = '' AND booked_ms <= {t:UInt64} GROUP BY campaign", Map.of("t", Long.toString(tMs)))
                        .forEach(r -> m.put(r.get("campaign").asText(), ClickHouseClient.num(r, "spend")));
                return m;
            }

            public String name() {
                return "ClickHouse (via Kafka)";
            }
        };
    }
}
