package io.adexchange.clickhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse over its HTTP interface. Values are never spliced into SQL: queries use ClickHouse's native
 * {@code {name:Type}} placeholders and the values travel as {@code param_name} query parameters, so user-supplied filters cannot inject SQL.
 */
public final class ClickHouseClient {

    public static final class ClickHouseException extends RuntimeException {
        public ClickHouseException(String message, Throwable cause) {
            super(message, cause);
        }

        public ClickHouseException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String authHeader;
    private final Duration timeout;

    public ClickHouseClient(String baseUrl, String user, String password) {
        this(baseUrl, user, password, Duration.ofSeconds(10));
    }

    public ClickHouseClient(String baseUrl, String user, String password, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.timeout = timeout;
    }

    /** Runs a SELECT and returns rows as JSON objects (numbers stay numbers; UInt64 arrives as a string in ClickHouse JSON, so use {@link #num}). */
    public List<JsonNode> query(String sql, Map<String, String> params) {
        StringBuilder url = new StringBuilder(baseUrl).append("/?default_format=JSONEachRow&output_format_json_quote_64bit_integers=0");
        params.forEach((k, v) -> url.append("&param_").append(enc(k)).append('=').append(enc(v)));
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout)
                    .header("Authorization", authHeader).POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new ClickHouseException("clickhouse " + r.statusCode() + ": " + abbreviate(r.body()));
            List<JsonNode> rows = new ArrayList<>();
            for (String line : r.body().split("\n")) if (!line.isBlank()) rows.add(JSON.readTree(line));
            return rows;
        } catch (IOException e) {
            throw new ClickHouseException("clickhouse unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClickHouseException("interrupted", e);
        }
    }

    public List<JsonNode> query(String sql) {
        return query(sql, Map.of());
    }

    public boolean ping() {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/ping")).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static long num(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return n == null || n.isNull() ? 0 : n.isNumber() ? n.asLong() : Long.parseLong(n.asText());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
