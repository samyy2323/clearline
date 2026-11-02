package io.adexchange.app.kafka;

import io.adexchange.app.campaign.PgCampaignStore;
import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.Campaign;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds and maintains the in-memory campaign index from the compacted {@code campaign-events} topic. Every engine reads the whole
 * topic from the beginning (no consumer group, no committed offsets), so a fresh instance reconstructs the same index without ever
 * calling Postgres, and a delete arrives as a tombstone. Events are applied in batches with a single index rebuild.
 */
public class CampaignEventConsumer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CampaignEventConsumer.class);

    private final String bootstrap;
    private final IndexHolder index;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean caughtUp;

    public final AtomicLong applied = new AtomicLong();
    public final AtomicLong skipped = new AtomicLong();
    private volatile long lastApplyMs;

    public CampaignEventConsumer(String bootstrap, IndexHolder index) {
        this.bootstrap = bootstrap;
        this.index = index;
        this.thread = Thread.ofPlatform().daemon().name("campaign-events-consumer").start(this::run);
    }

    /** True once the consumer has replayed everything that existed at startup. */
    public boolean caughtUp() {
        return caughtUp;
    }

    public long lastApplyMillis() {
        return lastApplyMs;
    }

    private void run() {
        KafkaSupport.ensureTopics(bootstrap);
        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.<String, Object>of(
                    "bootstrap.servers", bootstrap, "enable.auto.commit", false, "auto.offset.reset", "earliest",
                    "max.poll.records", 500, "request.timeout.ms", 10000, "default.api.timeout.ms", 15000),
                    new StringDeserializer(), new StringDeserializer())) {
                List<TopicPartition> parts = new ArrayList<>();
                for (PartitionInfo p : c.partitionsFor(KafkaSupport.CAMPAIGN_EVENTS)) parts.add(new TopicPartition(p.topic(), p.partition()));
                c.assign(parts);
                c.seekToBeginning(parts);
                Map<TopicPartition, Long> end = c.endOffsets(parts);
                caughtUp = end.values().stream().allMatch(o -> o == 0);
                while (running) {
                    var records = c.poll(Duration.ofMillis(250));
                    if (!records.isEmpty()) apply(records);
                    if (!caughtUp) {
                        caughtUp = parts.stream().allMatch(tp -> c.position(tp) >= end.get(tp));
                        if (caughtUp) log.info("campaign index caught up: {} campaigns", index.all().size());
                    }
                }
            } catch (Exception e) {
                if (!running) return;
                log.warn("campaign-events consumer error, retrying: {}", e.toString());
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void apply(Iterable<ConsumerRecord<String, String>> records) {
        // later records win: a compacted topic can replay an older upsert before a newer one for the same key
        Map<String, Campaign> upserts = new LinkedHashMap<>();
        Set<String> removes = new LinkedHashSet<>();
        for (ConsumerRecord<String, String> r : records) {
            if (r.value() == null) {
                upserts.remove(r.key());
                removes.add(r.key());
                continue;
            }
            try {
                Campaign c = PgCampaignStore.fromJson(r.value());
                removes.remove(c.id());
                upserts.put(c.id(), c);
            } catch (RuntimeException e) {
                skipped.incrementAndGet();
                log.warn("skipping bad campaign event key={}: {}", r.key(), e.getMessage());
            }
        }
        index.applyBatch(upserts.values(), removes);
        applied.addAndGet(upserts.size() + removes.size());
        lastApplyMs = System.currentTimeMillis();
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
