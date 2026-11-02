package io.adexchange.app.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class KafkaSupport {
    private static final Logger log = LoggerFactory.getLogger(KafkaSupport.class);

    public static final String CAMPAIGN_EVENTS = "campaign-events";
    public static final String AD_EVENTS = "ad-events";
    public static final String AD_EVENTS_DLQ = "ad-events.dlq";

    private KafkaSupport() {}

    /** Creates the topics if missing (idempotent); retries while the broker is still starting. */
    public static void ensureTopics(String bootstrap) {
        for (int attempt = 1; attempt <= 30; attempt++) {
            try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", 4000,
                    "default.api.timeout.ms", 6000))) {
                List<NewTopic> topics = List.of(
                        new NewTopic(CAMPAIGN_EVENTS, 1, (short) 1).configs(Map.of("cleanup.policy", "compact")),
                        new NewTopic(AD_EVENTS, 3, (short) 1),
                        new NewTopic(AD_EVENTS_DLQ, 1, (short) 1));
                for (var e : admin.createTopics(topics).values().entrySet()) {
                    try {
                        e.getValue().get(10, TimeUnit.SECONDS);
                    } catch (ExecutionException ex) {
                        if (!(ex.getCause() instanceof TopicExistsException)) throw ex;
                    }
                }
                return;
            } catch (Exception e) {
                log.warn("kafka not ready for topic setup (attempt {}): {}", attempt, e.toString());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("could not ensure kafka topics at {}", bootstrap);
    }
}
