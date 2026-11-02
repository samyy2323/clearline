package io.adexchange.app.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.auction.model.Campaign;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Full edition: campaigns in Postgres with a transactional outbox. A write inserts or updates the campaign row AND appends an outbox
 * row in ONE transaction, so the change event can never be lost or announced without the write. The relay publishes unpublished
 * outbox rows to the compacted Kafka topic {@value #TOPIC} (key = campaign id, tombstone on delete) and marks them published.
 * Delivery is at-least-once, in id order; the engines' index is idempotent.
 */
@Component
@ConditionalOnProperty(name = "exchange.mode", havingValue = "full")
public class PgCampaignStore implements CampaignStore, DisposableBean {

    public static final String TOPIC = "campaign-events";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Producer<String, String> producer;

    @Autowired
    public PgCampaignStore(JdbcTemplate jdbc, PlatformTransactionManager tm, ExchangeProperties props) {
        this(jdbc, tm, new KafkaProducer<>(producerConfig(props.kafkaBootstrap()), new StringSerializer(), new StringSerializer()));
    }

    public PgCampaignStore(JdbcTemplate jdbc, PlatformTransactionManager tm, Producer<String, String> producer) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.producer = producer;
    }

    static Map<String, Object> producerConfig(String bootstrap) {
        Map<String, Object> c = new HashMap<>();
        c.put("bootstrap.servers", bootstrap);
        c.put("acks", "all");
        c.put("enable.idempotence", true);
        c.put("max.block.ms", 5000);
        c.put("delivery.timeout.ms", 15000);
        c.put("request.timeout.ms", 5000);
        return c;
    }

    // ------------------------------------------------------------------ writes

    @Override
    public Campaign save(Campaign c) {
        tx.executeWithoutResult(s -> upsert(c));
        return c;
    }

    private void upsert(Campaign c) {
        jdbc.update("""
                INSERT INTO campaigns (id, advertiser_id, name, bid_cpm_micros, budget_micros, hour_mask, freq_cap, start_ms, end_ms, active, targeting)
                VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)
                ON CONFLICT (id) DO UPDATE SET advertiser_id = EXCLUDED.advertiser_id, name = EXCLUDED.name,
                  bid_cpm_micros = EXCLUDED.bid_cpm_micros, budget_micros = EXCLUDED.budget_micros, hour_mask = EXCLUDED.hour_mask,
                  freq_cap = EXCLUDED.freq_cap, start_ms = EXCLUDED.start_ms, end_ms = EXCLUDED.end_ms, active = EXCLUDED.active,
                  targeting = EXCLUDED.targeting, version = campaigns.version + 1, updated_at = now()
                """, c.id(), c.advertiserId(), c.name(), c.bidCpmMicros(), c.budgetMicros(), c.hourMask(), c.freqCap(),
                c.startMs(), c.endMs(), c.active(), targetingJson(c));
        jdbc.update("INSERT INTO outbox (aggregate_id, event_type, payload) VALUES (?, 'UPSERT', ?::jsonb)", c.id(), toJson(c));
    }

    @Override
    public boolean delete(String id) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            if (jdbc.update("DELETE FROM campaigns WHERE id = ?", id) == 0) return false;
            jdbc.update("INSERT INTO outbox (aggregate_id, event_type, payload) VALUES (?, 'DELETE', NULL)", id);
            return true;
        }));
    }

    @Override
    public void replaceAll(Collection<Campaign> cs) {
        Set<String> keep = cs.stream().map(Campaign::id).collect(Collectors.toSet());
        tx.executeWithoutResult(s -> {
            for (String id : jdbc.queryForList("SELECT id FROM campaigns", String.class)) {
                if (!keep.contains(id)) {
                    jdbc.update("DELETE FROM campaigns WHERE id = ?", id);
                    jdbc.update("INSERT INTO outbox (aggregate_id, event_type, payload) VALUES (?, 'DELETE', NULL)", id);
                }
            }
            cs.forEach(this::upsert);
        });
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Campaign get(String id) {
        List<Campaign> r = jdbc.query("SELECT * FROM campaigns WHERE id = ?", (rs, i) -> map(rs), id);
        return r.isEmpty() ? null : r.get(0);
    }

    @Override
    public Collection<Campaign> all() {
        return jdbc.query("SELECT * FROM campaigns ORDER BY id", (rs, i) -> map(rs));
    }

    @Override
    public List<Campaign> forAdvertiser(String adv) {
        return jdbc.query("SELECT * FROM campaigns WHERE advertiser_id = ? ORDER BY id", (rs, i) -> map(rs), adv);
    }

    @Override
    public int outboxDepth() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ outbox relay

    @Override
    public int relay() {
        Integer n = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, aggregate_id, payload::text AS payload FROM outbox
                    WHERE published_at IS NULL ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED""");
            if (rows.isEmpty()) return 0;
            List<Long> ids = new ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                try {
                    // synchronous send keeps per-key order and makes "published" mean "acknowledged by the broker"
                    producer.send(new ProducerRecord<>(TOPIC, (String) r.get("aggregate_id"), (String) r.get("payload")))
                            .get(8, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (ExecutionException | TimeoutException e) {
                    throw new IllegalStateException("kafka publish failed; outbox rows stay unpublished: " + e.getMessage(), e);
                }
                ids.add(((Number) r.get("id")).longValue());
            }
            jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ANY (?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids.toArray())));
            return ids.size();
        });
        return n == null ? 0 : n;
    }

    @Override
    public void destroy() {
        producer.close(java.time.Duration.ofSeconds(2));
    }

    // ------------------------------------------------------------------ mapping

    static String toJson(Campaign c) {
        try {
            return JSON.writeValueAsString(c);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Campaign fromJson(String json) {
        try {
            return JSON.readValue(json, Campaign.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("bad campaign event: " + e.getMessage(), e);
        }
    }

    private static String targetingJson(Campaign c) {
        ObjectNode n = JSON.createObjectNode();
        n.set("geos", JSON.valueToTree(new TreeSet<>(c.geos())));
        n.set("devices", JSON.valueToTree(new TreeSet<>(c.devices())));
        n.set("segments", JSON.valueToTree(new TreeSet<>(c.segments())));
        n.set("categories", JSON.valueToTree(new TreeSet<>(c.categories())));
        n.set("sizes", JSON.valueToTree(c.sizes().stream().map(sz -> sz.w() + "x" + sz.h()).sorted().toList()));
        return n.toString();
    }

    private static Set<String> set(JsonNode n, String f) {
        Set<String> s = new HashSet<>();
        n.path(f).forEach(x -> s.add(x.asText()));
        return s;
    }

    private static Campaign map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            JsonNode t = JSON.readTree(rs.getString("targeting"));
            Set<Campaign.Size> sizes = new HashSet<>();
            for (String sz : set(t, "sizes")) {
                String[] p = sz.split("x");
                sizes.add(new Campaign.Size(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
            }
            return new Campaign(rs.getString("id"), rs.getString("advertiser_id"), rs.getString("name"), rs.getLong("bid_cpm_micros"),
                    rs.getLong("budget_micros"), set(t, "geos"), set(t, "devices"), set(t, "segments"), set(t, "categories"),
                    rs.getInt("hour_mask"), sizes, rs.getInt("freq_cap"), rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getBoolean("active"));
        } catch (JsonProcessingException e) {
            throw new java.sql.SQLException("bad targeting json for " + rs.getString("id"), e);
        }
    }
}
