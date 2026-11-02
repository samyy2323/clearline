package io.adexchange.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import io.adexchange.auth.Jwt;
import io.adexchange.clickhouse.ClickHouseClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Read-side queries over the ClickHouse rollups. Tenancy: an advertiser only ever sees rows where {@code advertiser} equals their
 * own id, enforced here in the query, not in the client. All user-controlled values are bound as ClickHouse parameters; the only
 * thing spliced into SQL is a column name taken from a fixed whitelist.
 */
@RestController
@RequestMapping("/api/reports")
class ReportsController {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Map<String, String> DIMENSIONS = Map.of(
            "campaign", "campaign", "geo", "geo", "device", "device", "publisher", "publisher", "arm", "arm");

    private record Subscriber(SseEmitter emitter, Jwt.Claims claims) {}

    private final ClickHouseClient ch;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    ReportsController(ClickHouseClient ch) {
        this.ch = ch;
    }

    private static Jwt.Claims claims(HttpServletRequest r) {
        return (Jwt.Claims) r.getAttribute(AuthFilter.ATTR);
    }

    private static int minutes(int m) {
        if (m < 1 || m > 1440) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes must be 1..1440");
        return m;
    }

    private static String tenant(Jwt.Claims c, Map<String, String> params) {
        if (c.admin()) return "";
        params.put("adv", c.advertiserId());
        return " AND advertiser = {adv:String}";
    }

    private List<JsonNode> run(String sql, Map<String, String> params) {
        try {
            return ch.query(sql, params);
        } catch (ClickHouseClient.ClickHouseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "analytics store unavailable");
        }
    }

    @GetMapping("/summary")
    Map<String, Object> summary(@RequestParam(defaultValue = "60") int minutes, HttpServletRequest req) {
        return summaryFor(claims(req), minutes(minutes));
    }

    private Map<String, Object> summaryFor(Jwt.Claims c, int minutes) {
        Map<String, String> p = new HashMap<>();
        p.put("m", Integer.toString(minutes));
        String t = tenant(c, p);
        List<JsonNode> rows = run("SELECT sumMerge(wins) AS wins, sumMerge(imps) AS imps, sumMerge(clicks) AS clicks, "
                + "sumMerge(spend_micros) AS spend FROM clearline.rollup_minute "
                + "WHERE minute >= now() - INTERVAL {m:UInt32} MINUTE" + t, p);
        JsonNode r = rows.isEmpty() ? null : rows.get(0);
        long imps = r == null ? 0 : ClickHouseClient.num(r, "imps"), clicks = r == null ? 0 : ClickHouseClient.num(r, "clicks");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minutes", minutes);
        m.put("wins", r == null ? 0 : ClickHouseClient.num(r, "wins"));
        m.put("impressions", imps);
        m.put("clicks", clicks);
        m.put("ctr", imps == 0 ? 0 : (double) clicks / imps);
        m.put("spendMicros", r == null ? 0 : ClickHouseClient.num(r, "spend"));
        return m;
    }

    @GetMapping("/campaigns")
    List<Map<String, Object>> campaigns(@RequestParam(defaultValue = "60") int minutes, HttpServletRequest req) {
        Map<String, String> p = new HashMap<>();
        p.put("m", Integer.toString(minutes(minutes)));
        String t = tenant(claims(req), p);
        return rows(run("SELECT campaign, sumMerge(imps) AS imps, sumMerge(clicks) AS clicks, sumMerge(spend_micros) AS spend, "
                + "sumMerge(pctr_sum) AS pctr_sum FROM clearline.rollup_minute WHERE minute >= now() - INTERVAL {m:UInt32} MINUTE" + t
                + " GROUP BY campaign ORDER BY spend DESC LIMIT 200", p), "campaign");
    }

    @GetMapping("/timeseries")
    List<Map<String, Object>> timeseries(@RequestParam(defaultValue = "60") int minutes, @RequestParam(required = false) String campaign,
                                         HttpServletRequest req) {
        Map<String, String> p = new HashMap<>();
        p.put("m", Integer.toString(minutes(minutes)));
        String t = tenant(claims(req), p);
        if (campaign != null && !campaign.isBlank()) {
            if (!ID.matcher(campaign).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad campaign id");
            p.put("c", campaign);
            t += " AND campaign = {c:String}";
        }
        return rows(run("SELECT toUInt32(minute) AS t, sumMerge(imps) AS imps, sumMerge(clicks) AS clicks, sumMerge(spend_micros) AS spend "
                + "FROM clearline.rollup_minute WHERE minute >= now() - INTERVAL {m:UInt32} MINUTE" + t + " GROUP BY minute ORDER BY minute", p), null);
    }

    @GetMapping("/breakdown")
    List<Map<String, Object>> breakdown(@RequestParam String by, @RequestParam(defaultValue = "60") int minutes, HttpServletRequest req) {
        String col = DIMENSIONS.get(by);
        if (col == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "by must be one of " + new TreeSet<>(DIMENSIONS.keySet()));
        Map<String, String> p = new HashMap<>();
        p.put("m", Integer.toString(minutes(minutes)));
        String t = tenant(claims(req), p);
        return rows(run("SELECT " + col + " AS k, sumMerge(imps) AS imps, sumMerge(clicks) AS clicks, sumMerge(spend_micros) AS spend "
                + "FROM clearline.rollup_minute WHERE minute >= now() - INTERVAL {m:UInt32} MINUTE" + t
                + " GROUP BY k ORDER BY spend DESC LIMIT 50", p), "k");
    }

    /** Fraud and duplicate counts are an operator view, not an advertiser one. */
    @GetMapping("/flags")
    List<Map<String, Object>> flags(@RequestParam(defaultValue = "60") int minutes, HttpServletRequest req) {
        if (!claims(req).admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        return rows(run("SELECT flag, sum(n) AS n FROM clearline.flags_minute WHERE minute >= now() - INTERVAL {m:UInt32} MINUTE "
                + "GROUP BY flag ORDER BY n DESC", Map.of("m", Integer.toString(minutes(minutes)))), "flag");
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    SseEmitter stream(HttpServletRequest req) {
        Jwt.Claims c = claims(req);
        SseEmitter e = new SseEmitter(0L);
        Subscriber s = new Subscriber(e, c);
        subscribers.add(s);
        e.onCompletion(() -> subscribers.remove(s));
        e.onTimeout(() -> subscribers.remove(s));
        e.onError(t -> subscribers.remove(s));
        return e;
    }

    @Scheduled(fixedRate = 2000)
    void push() {
        for (Subscriber s : subscribers) {
            try {
                s.emitter().send(SseEmitter.event().name("summary").data(summaryFor(s.claims(), 5)));
            } catch (IOException | RuntimeException ex) {
                subscribers.remove(s);
            }
        }
    }

    private static List<Map<String, Object>> rows(List<JsonNode> in, String keyField) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode r : in) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().isNumber() ? f.getValue().numberValue() : f.getValue().asText()));
            out.add(m);
        }
        return out;
    }
}
