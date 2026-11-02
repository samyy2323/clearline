package io.adexchange.app.stats;

import io.adexchange.app.config.ExchangeProperties;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Full edition: the dashboard talks to one origin, and this forwards report queries to the reporting service (which re-verifies the
 * JWT and applies tenant scoping itself). Using the auto-configured RestClient.Builder means trace context is propagated, so one
 * trace spans browser request -> app -> reporting -> ClickHouse.
 */
@RestController
@ConditionalOnProperty(name = "exchange.mode", havingValue = "full")
class ReportingProxyController {

    private final RestClient client;

    ReportingProxyController(RestClient.Builder builder, ObservationRegistry observations, ExchangeProperties props) {
        // explicit registry: this is what makes the client span (and the traceparent header) exist, so the trace continues in reporting
        this.client = builder.observationRegistry(observations).baseUrl(props.reportingUrl()).build();
    }

    @GetMapping({"/api/reports/summary", "/api/reports/campaigns", "/api/reports/timeseries", "/api/reports/breakdown", "/api/reports/flags"})
    ResponseEntity<byte[]> forward(HttpServletRequest req) {
        String uri = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        try {
            byte[] body = client.get().uri(uri).header("Authorization", req.getHeader("Authorization")).retrieve().body(byte[].class);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsByteArray());
        } catch (RestClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"reporting service unavailable\"}".getBytes());
        }
    }
}
