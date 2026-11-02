package io.adexchange.app.track;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Lite edition: events stay in the in-memory aggregates; nothing is shipped anywhere. */
@Component
@ConditionalOnProperty(name = "exchange.mode", havingValue = "lite", matchIfMissing = true)
public class NoopEventSink implements AdEventSink {

    @Override
    public void publish(AdEvent event) {}

    @Override
    public Map<String, Object> stats() {
        return Map.of("sink", "none (lite)");
    }
}
