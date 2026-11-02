package io.adexchange.app.track;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.app.kafka.KafkaSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ships events to the {@code ad-events} topic without ever putting Kafka on the request path.
 * <ul>
 *   <li>{@link #publish} only offers to a bounded queue; when the queue is full the event is dropped and counted (load shedding).</li>
 *   <li>A single sender thread drains the queue into an async producer ({@code max.block.ms=200}, so a dead broker costs the sender
 *       thread 200 ms per attempt, never a request thread).</li>
 *   <li>An event whose delivery fails after the producer's retries goes to the dead-letter topic {@code ad-events.dlq} with the
 *       error in a header; if even that fails it is counted as lost.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "exchange.mode", havingValue = "full")
public class KafkaEventSink implements AdEventSink, DisposableBean {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BlockingQueue<AdEvent> queue;
    private final Producer<String, String> producer;
    private final Thread sender;
    private volatile boolean running = true;

    public final AtomicLong sent = new AtomicLong();
    public final AtomicLong acked = new AtomicLong();
    public final AtomicLong dropped = new AtomicLong();
    public final AtomicLong sendFailed = new AtomicLong();
    public final AtomicLong deadLettered = new AtomicLong();
    public final AtomicLong lost = new AtomicLong();

    @Autowired
    public KafkaEventSink(ExchangeProperties props) {
        this(new KafkaProducer<>(producerConfig(props.kafkaBootstrap()), new StringSerializer(), new StringSerializer()), 50_000);
        Thread.ofPlatform().daemon().name("kafka-topic-setup").start(() -> KafkaSupport.ensureTopics(props.kafkaBootstrap()));
    }

    public KafkaEventSink(Producer<String, String> producer, int queueCapacity) {
        this.producer = producer;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.sender = Thread.ofPlatform().daemon().name("kafka-event-sender").start(this::drain);
    }

    static Map<String, Object> producerConfig(String bootstrap) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("bootstrap.servers", bootstrap);
        c.put("acks", "1");
        c.put("linger.ms", 20);
        c.put("batch.size", 65536);
        c.put("compression.type", "lz4");
        c.put("max.block.ms", 200);
        c.put("request.timeout.ms", 3000);
        c.put("delivery.timeout.ms", 8000);
        c.put("enable.idempotence", false);
        return c;
    }

    @Override
    public void publish(AdEvent e) {
        if (!queue.offer(e)) dropped.incrementAndGet();
    }

    private void drain() {
        while (running) {
            AdEvent e;
            try {
                e = queue.take();
            } catch (InterruptedException ie) {
                return;
            }
            String json;
            try {
                json = JSON.writeValueAsString(e);
            } catch (JsonProcessingException ex) {
                lost.incrementAndGet();
                continue;
            }
            try {
                producer.send(new ProducerRecord<>(KafkaSupport.AD_EVENTS, e.campaign(), json), (md, err) -> {
                    if (err == null) acked.incrementAndGet();
                    else deadLetter(e.campaign(), json, err);
                });
                sent.incrementAndGet();
            } catch (RuntimeException ex) {
                sendFailed.incrementAndGet(); // broker unreachable (metadata wait exceeded max.block.ms): shed, do not crash
            }
        }
    }

    private void deadLetter(String key, String json, Exception cause) {
        try {
            ProducerRecord<String, String> r = new ProducerRecord<>(KafkaSupport.AD_EVENTS_DLQ, key, json);
            r.headers().add(new RecordHeader("error", String.valueOf(cause).getBytes(StandardCharsets.UTF_8)));
            producer.send(r, (md, err) -> {
                if (err == null) deadLettered.incrementAndGet();
                else lost.incrementAndGet();
            });
        } catch (RuntimeException ex) {
            lost.incrementAndGet();
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sink", "kafka");
        m.put("queueDepth", queue.size());
        m.put("sent", sent.get());
        m.put("acked", acked.get());
        m.put("dropped", dropped.get());
        m.put("sendFailed", sendFailed.get());
        m.put("deadLettered", deadLettered.get());
        m.put("lost", lost.get());
        return m;
    }

    @Override
    public void destroy() {
        running = false;
        sender.interrupt();
        try {
            producer.flush();
        } catch (RuntimeException ignored) {
            // shutting down
        }
        producer.close(java.time.Duration.ofSeconds(2));
    }
}
