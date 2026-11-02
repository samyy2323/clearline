package io.adexchange.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import io.adexchange.clickhouse.ClickHouseClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the REAL deploy/clickhouse/init.sql (materialized views, AggregatingMergeTree rollups, flag counts) against a ClickHouse
 * container. Only the Kafka-engine table is swapped for a Null-engine table so that inserts fire the views deterministically; the
 * Kafka-to-ClickHouse transport itself is covered by the compose-stack run (see docs), not by this test.
 */
@Testcontainers(disabledWithoutDocker = true)
class ClickHouseRollupIT {

    @Container
    static GenericContainer<?> ch = new GenericContainer<>(DockerImageName.parse("clickhouse/clickhouse-server:24.8-alpine"))
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test").withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123).waitingFor(Wait.forHttp("/ping").forPort(8123));

    static ClickHouseClient client;
    // recent, minute-aligned timestamps: the tables carry real TTLs (30 / 90 days) that would purge old test data at merge time
    static final long MIN = (System.currentTimeMillis() / 60_000 - 10) * 60_000;

    @BeforeAll
    static void init() throws Exception {
        client = new ClickHouseClient("http://" + ch.getHost() + ":" + ch.getMappedPort(8123), "test", "test");
        String sql = Files.readString(Path.of("../../deploy/clickhouse/init.sql"));
        sql = sql.replaceAll("(?m)^--.*$", "");
        sql = sql.replaceFirst("(?s)ENGINE = Kafka.*?;", "ENGINE = Null;");
        for (String stmt : sql.split(";")) if (!stmt.isBlank()) client.query(stmt.trim());
    }

    static String ev(String type, String id, long tsMs, String campaign, String adv, String geo, long spend, String flag) {
        return String.format("{\"type\":\"%s\",\"id\":\"%s\",\"ts_ms\":%d,\"booked_ms\":%d,\"campaign\":\"%s\",\"advertiser\":\"%s\",\"publisher\":\"pub1\","
                + "\"geo\":\"%s\",\"device\":\"mobile\",\"category\":\"news\",\"arm\":\"gsp\",\"user\":\"u\",\"hour\":14,"
                + "\"price_cpm_micros\":4000000,\"spend_micros\":%d,\"pctr\":0.02,\"flag\":\"%s\"}", type, id, tsMs, tsMs, campaign, adv, geo, spend, flag);
    }

    static void insert(String... events) {
        client.query("INSERT INTO clearline.ad_events_queue FORMAT JSONEachRow\n" + String.join("\n", events));
    }

    static JsonNode one(String sql, Map<String, String> p) {
        List<JsonNode> r = client.query(sql, p);
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    @Test
    void rollupsAggregateCleanEventsAcrossInsertBatchesAndCountFlaggedSeparately() {
        insert(ev("imp", "a1", MIN + 1_000, "c1", "adv-1", "IN", 4_000, ""),
               ev("imp", "a2", MIN + 2_000, "c1", "adv-1", "IN", 4_000, ""),
               ev("click", "a1", MIN + 3_000, "c1", "adv-1", "IN", 0, ""),
               ev("win", "a1", MIN + 500, "c1", "adv-1", "IN", 0, ""));
        // second batch lands in the SAME minute: AggregatingMergeTree must combine, not overwrite
        insert(ev("imp", "a3", MIN + 4_000, "c1", "adv-1", "US", 4_000, ""),
               ev("imp", "a1", MIN + 5_000, "c1", "adv-1", "IN", 0, "DUPLICATE"),
               ev("imp", "a9", MIN + 6_000, "c1", "adv-1", "IN", 0, "BOT_UA"),
               ev("click", "zz", MIN + 7_000, "c1", "adv-1", "IN", 0, "CLICK_WITHOUT_IMPRESSION"));

        JsonNode t = one("SELECT sumMerge(imps) AS imps, sumMerge(clicks) AS clicks, sumMerge(wins) AS wins, sumMerge(spend_micros) AS spend "
                + "FROM clearline.rollup_minute WHERE campaign = {c:String}", Map.of("c", "c1"));
        assertThat(ClickHouseClient.num(t, "imps")).as("flagged impressions are not counted").isEqualTo(3);
        assertThat(ClickHouseClient.num(t, "clicks")).isEqualTo(1);
        assertThat(ClickHouseClient.num(t, "wins")).isEqualTo(1);
        assertThat(ClickHouseClient.num(t, "spend")).as("billed spend excludes duplicates").isEqualTo(12_000);

        List<JsonNode> flags = client.query("SELECT flag, sum(n) AS n FROM clearline.flags_minute GROUP BY flag ORDER BY flag");
        assertThat(flags).extracting(r -> r.get("flag").asText() + "=" + ClickHouseClient.num(r, "n"))
                .containsExactly("BOT_UA=1", "CLICK_WITHOUT_IMPRESSION=1", "DUPLICATE=1");

        assertThat(ClickHouseClient.num(one("SELECT count() AS n FROM clearline.ad_events", Map.of()), "n"))
                .as("the raw log keeps everything, including flagged events").isEqualTo(8);
    }

    @Test
    void breakdownByDimensionAndPerMinuteBucketsAreCorrect() {
        long m2 = MIN + 120_000;
        insert(ev("imp", "b1", MIN + 1_000, "c2", "adv-2", "IN", 1_000, ""),
               ev("imp", "b2", m2 + 1_000, "c2", "adv-2", "DE", 2_000, ""),
               ev("imp", "b3", m2 + 2_000, "c2", "adv-2", "DE", 2_000, ""));
        List<JsonNode> byGeo = client.query("SELECT geo AS k, sumMerge(imps) AS imps, sumMerge(spend_micros) AS spend FROM clearline.rollup_minute "
                + "WHERE campaign = {c:String} GROUP BY k ORDER BY spend DESC", Map.of("c", "c2"));
        assertThat(byGeo).extracting(r -> r.get("k").asText()).containsExactly("DE", "IN");
        assertThat(ClickHouseClient.num(byGeo.get(0), "spend")).isEqualTo(4_000);

        List<JsonNode> perMinute = client.query("SELECT minute, sumMerge(imps) AS imps FROM clearline.rollup_minute "
                + "WHERE campaign = {c:String} GROUP BY minute ORDER BY minute", Map.of("c", "c2"));
        assertThat(perMinute).hasSize(2);
        assertThat(ClickHouseClient.num(perMinute.get(0), "imps")).isEqualTo(1);
        assertThat(ClickHouseClient.num(perMinute.get(1), "imps")).isEqualTo(2);
    }

    @Test
    void tenantScopingAndBoundParametersCannotBeBypassed() {
        insert(ev("imp", "t1", MIN + 1_000, "c3", "adv-3", "IN", 500, ""),
               ev("imp", "t2", MIN + 1_000, "c4", "adv-4", "IN", 700, ""));
        JsonNode mine = one("SELECT sumMerge(spend_micros) AS spend FROM clearline.rollup_minute WHERE advertiser = {adv:String}", Map.of("adv", "adv-3"));
        assertThat(ClickHouseClient.num(mine, "spend")).isEqualTo(500);
        // an injection attempt is just a string that matches no advertiser
        JsonNode evil = one("SELECT sumMerge(spend_micros) AS spend FROM clearline.rollup_minute WHERE advertiser = {adv:String}",
                Map.of("adv", "adv-3' OR '1'='1"));
        assertThat(ClickHouseClient.num(evil, "spend")).isZero();
        assertThatThrownBy(() -> client.query("SELECT * FROM table_that_does_not_exist"))
                .isInstanceOf(ClickHouseClient.ClickHouseException.class);
    }

    @Test
    void billedSpendCanBeTotalledByBookingTimeNotBillingTime() {
        // booked 10 min ago and billed just now vs booked just now: the cohort query must split them by BOOKING time
        long booked = MIN + 1_000, billedAt = MIN + 600_000;
        insert(String.format("{\"type\":\"imp\",\"id\":\"k1\",\"ts_ms\":%d,\"booked_ms\":%d,\"campaign\":\"c5\",\"advertiser\":\"adv-5\","
                        + "\"publisher\":\"p\",\"geo\":\"IN\",\"device\":\"mobile\",\"category\":\"news\",\"arm\":\"gsp\",\"user\":\"u\",\"hour\":1,"
                        + "\"price_cpm_micros\":1,\"spend_micros\":3000,\"pctr\":0.01,\"flag\":\"\"}", billedAt, booked),
                String.format("{\"type\":\"imp\",\"id\":\"k2\",\"ts_ms\":%d,\"booked_ms\":%d,\"campaign\":\"c5\",\"advertiser\":\"adv-5\","
                        + "\"publisher\":\"p\",\"geo\":\"IN\",\"device\":\"mobile\",\"category\":\"news\",\"arm\":\"gsp\",\"user\":\"u\",\"hour\":1,"
                        + "\"price_cpm_micros\":1,\"spend_micros\":5000,\"pctr\":0.01,\"flag\":\"\"}", billedAt + 1_000, billedAt));
        String q = "SELECT sum(spend_micros) AS spend FROM clearline.ad_events WHERE campaign = {c:String} AND type = 'imp' AND flag = '' AND booked_ms <= {t:UInt64}";
        assertThat(ClickHouseClient.num(one(q, Map.of("c", "c5", "t", Long.toString(booked + 2_000))), "spend"))
                .as("only the early booking, although both were billed after the cutoff").isEqualTo(3_000);
        assertThat(ClickHouseClient.num(one(q, Map.of("c", "c5", "t", Long.toString(billedAt))), "spend")).isEqualTo(8_000);
    }
}
