package io.adexchange.app;

import io.adexchange.app.track.AdEvent;
import io.adexchange.app.track.KafkaEventSink;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class KafkaEventSinkTest {

    static AdEvent event(String id) {
        return new AdEvent("imp", id, 1_700_000_000_000L, 1_699_999_000_000L, "c1", "adv-1", "pub1", "IN", "mobile", "news", "gsp", "u1", 14, 4_000_000, 4_000, 0.02, "");
    }

    static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertThat(c.getAsBoolean()).isTrue();
    }

    @Test
    void deliversEventsAsJsonKeyedByCampaign() throws Exception {
        var producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        var sink = new KafkaEventSink(producer, 100);
        sink.publish(event("r1|s0|c1"));
        await(() -> producer.history().size() == 1);
        var rec = producer.history().get(0);
        assertThat(rec.topic()).isEqualTo("ad-events");
        assertThat(rec.key()).isEqualTo("c1");
        assertThat(rec.value()).contains("\"ts_ms\":1700000000000").contains("\"spend_micros\":4000").contains("\"type\":\"imp\"");
        sink.destroy();
    }

    @Test
    void failedDeliveryGoesToTheDeadLetterTopicWithTheErrorInAHeader() throws Exception {
        var producer = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        var sink = new KafkaEventSink(producer, 100);
        sink.publish(event("r1|s0|c1"));
        await(() -> !producer.history().isEmpty());
        producer.errorNext(new TimeoutException("broker said no"));
        await(() -> producer.history().size() == 2);
        var dlq = producer.history().get(1);
        assertThat(dlq.topic()).isEqualTo("ad-events.dlq");
        assertThat(new String(dlq.headers().lastHeader("error").value())).contains("broker said no");
        producer.completeNext();
        await(() -> sink.deadLettered.get() == 1);
        sink.destroy();
    }

    @Test
    void whenTheSenderIsStuckTheBufferFillsThenEventsAreDroppedAndCountedNotBlocked() throws Exception {
        @SuppressWarnings("unchecked")
        Producer<String, String> stuck = mock(Producer.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS); // a broker that never answers: the sender thread parks here
            return mock(Future.class);
        }).when(stuck).send(any(ProducerRecord.class), any(Callback.class));

        var sink = new KafkaEventSink(stuck, 3);
        sink.publish(event("e0"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        for (int i = 1; i <= 10; i++) sink.publish(event("e" + i)); // 3 fit in the queue, 7 must be shed
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(sink.dropped.get()).isEqualTo(7);
        assertThat(tookMs).as("publishing never blocks the request path").isLessThan(200);
        release.countDown();
        sink.destroy();
    }

    @Test
    void anUnreachableBrokerIsShedNotFatal() throws Exception {
        @SuppressWarnings("unchecked")
        Producer<String, String> down = mock(Producer.class);
        doAnswer(inv -> { throw new TimeoutException("metadata not available"); }).when(down).send(any(ProducerRecord.class), any(Callback.class));
        var sink = new KafkaEventSink(down, 100);
        for (int i = 0; i < 5; i++) sink.publish(event("e" + i));
        await(() -> sink.sendFailed.get() == 5);
        sink.publish(event("after")); // still accepting, still alive
        await(() -> sink.sendFailed.get() == 6);
        sink.destroy();
    }
}
