package io.adexchange.reporting;

import io.adexchange.auth.Jwt;
import io.adexchange.clickhouse.ClickHouseClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ReportingConfig {

    @Bean
    ClickHouseClient clickHouse(@Value("${reporting.clickhouse-url}") String url, @Value("${reporting.clickhouse-user}") String user,
                                @Value("${reporting.clickhouse-password}") String password) {
        return new ClickHouseClient(url, user, password);
    }

    /** Same signing secret as the exchange app, so a token issued there is accepted here. */
    @Bean
    Jwt jwt(@Value("${reporting.jwt-secret:}") String secret) {
        return new Jwt(secret == null || secret.isBlank() ? "dev-jwt-secret-change-me-dev-jwt-secret" : secret);
    }
}
