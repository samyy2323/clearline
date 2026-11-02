package io.adexchange.app;

import io.adexchange.app.campaign.PgCampaignStore;
import io.adexchange.app.kafka.CampaignEventConsumer;
import io.adexchange.app.kafka.KafkaSupport;
import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.Campaign;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transactional outbox against real Postgres and Kafka: a write and its change event are atomic, events reach a fresh engine through the
 * compacted topic (including replay from the beginning), and a delete travels as a tombstone.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgOutboxKafkaIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    static JdbcTemplate jdbc;
    static PgCampaignStore store;
    static IndexHolder index = new IndexHolder();
    static CampaignEventConsumer consumer;

    @BeforeAll
    static void setUp() {
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        KafkaSupport.ensureTopics(kafka.getBootstrapServers());
        store = new PgCampaignStore(jdbc, new DataSourceTransactionManager(ds),
                new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers(), "acks", "all",
                        "enable.idempotence", true, "max.block.ms", 5000), new StringSerializer(), new StringSerializer()));
        consumer = new CampaignEventConsumer(kafka.getBootstrapServers(), index);
    }

    @AfterAll
    static void tearDown() {
        consumer.close();
        store.destroy();
    }

    static Campaign campaign(String id, long bid) {
        return new Campaign(id, "adv-1", "Nike | " + id, bid, 50_000_000L, Set.of("IN"), Set.of(), Set.of("sports"), Set.of(),
                0, Set.of(new Campaign.Size(300, 250)), 3, 1_000L, 9_000_000_000_000L, true);
    }

    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) Assertions.fail("timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    @Test
    @Order(1)
    void campaignRoundTripsThroughPostgresRowsIntact() {
        store.save(campaign("c-1", 2_000_000));
        Campaign back = store.get("c-1");
        assertThat(back.name()).isEqualTo("Nike | c-1");
        assertThat(back.geos()).containsExactly("IN");
        assertThat(back.sizes()).containsExactly(new Campaign.Size(300, 250));
        assertThat(back.freqCap()).isEqualTo(3);
        assertThat(store.outboxDepth()).as("one unpublished event").isEqualTo(1);
    }

    @Test
    @Order(2)
    void relayPublishesToKafkaAndTheEngineIndexSeesIt() throws Exception {
        assertThat(store.relay()).isEqualTo(1);
        assertThat(store.outboxDepth()).isZero();
        await(() -> index.get("c-1") != null, "index to receive c-1");
        assertThat(index.get("c-1").bidCpmMicros()).isEqualTo(2_000_000);
    }

    @Test
    @Order(3)
    void updatesAreIdempotentAndLastWriteWins() throws Exception {
        store.save(campaign("c-1", 3_000_000));
        store.save(campaign("c-1", 4_000_000));
        store.relay();
        await(() -> index.get("c-1").bidCpmMicros() == 4_000_000, "latest bid to win");
        assertThat(jdbc.queryForObject("select version from campaigns where id='c-1'", Long.class)).isEqualTo(3L);
    }

    @Test
    @Order(4)
    void aFailedWriteLeavesNeitherARowNorAnOutboxEvent() {
        int outboxBefore = jdbc.queryForObject("select count(*) from outbox", Integer.class);
        // bid of 0 violates the CHECK constraint on the campaigns row: the whole transaction must roll back
        assertThatThrownBy(() -> store.save(campaign("c-bad", 0))).isInstanceOf(Exception.class);
        assertThat(store.get("c-bad")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isEqualTo(outboxBefore);
    }

    @Test
    @Order(5)
    void deleteTravelsAsATombstoneAndRemovesFromTheIndex() throws Exception {
        store.save(campaign("c-2", 1_500_000));
        store.relay();
        await(() -> index.get("c-2") != null, "c-2 to arrive");
        assertThat(store.delete("c-2")).isTrue();
        store.relay();
        await(() -> index.get("c-2") == null, "tombstone to remove c-2");
        assertThat(store.delete("c-2")).as("second delete is a no-op").isFalse();
    }

    @Test
    @Order(6)
    void aBrandNewEngineRebuildsTheSameIndexFromTheTopicAlone() throws Exception {
        IndexHolder fresh = new IndexHolder();
        try (CampaignEventConsumer c2 = new CampaignEventConsumer(kafka.getBootstrapServers(), fresh)) {
            await(() -> c2.caughtUp() && fresh.get("c-1") != null, "fresh engine to catch up");
            assertThat(fresh.get("c-1").bidCpmMicros()).as("latest value, not an older replayed one").isEqualTo(4_000_000);
            assertThat(fresh.get("c-2")).as("deleted campaign stays deleted after replay").isNull();
        }
    }

    @Test
    @Order(7)
    void replaceAllRemovesCampaignsNotInTheNewSet() throws Exception {
        store.replaceAll(java.util.List.of(campaign("c-3", 1_000_000)));
        store.relay();
        await(() -> index.get("c-3") != null && index.get("c-1") == null, "replaceAll to propagate");
        assertThat(store.all()).extracting(Campaign::id).containsExactly("c-3");
    }
}
