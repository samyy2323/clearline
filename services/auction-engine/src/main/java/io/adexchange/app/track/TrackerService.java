package io.adexchange.app.track;

import io.adexchange.app.config.ExchangeProperties;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Win / impression / click ingestion. Order of checks: signature+expiry, bot UA, dedupe, click-without-impression,
 * click-rate limits. Suspicious events are flagged and counted (never silently dropped) and are never billed.
 */
@Service
public class TrackerService {

    public enum Outcome { ACCEPTED, DUPLICATE, FLAGGED_BOT, FLAGGED_NO_IMPRESSION, FLAGGED_CLICK_FLOOD, REJECTED_SIGNATURE, REJECTED_EXPIRED, REJECTED_MALFORMED }

    private static final Pattern BOT = Pattern.compile(
            "(?i)(bot|crawler|spider|headless|phantomjs|selenium|curl/|wget/|python-requests|scrapy|httpclient)");
    static final int MAX_CLICKS_PER_USER_PER_MIN = 5;
    static final int MAX_CLICKS_PER_IP_PER_MIN = 60;

    private final TrackingSigner signer;
    private volatile BillingLedger ledger = new BillingLedger(2_000_000);
    private volatile RotatingDedupe clickSeen = new RotatingDedupe(2_000_000);
    private volatile RotatingDedupe winSeen = new RotatingDedupe(2_000_000);
    private final EventStats stats;
    private final EventLog eventLog;
    private final AdEventSink sink;

    private final Map<String, AtomicInteger> userClicks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> ipClicks = new ConcurrentHashMap<>();
    private volatile long windowStart = System.currentTimeMillis();

    public TrackerService(ExchangeProperties props, EventStats stats, EventLog eventLog, AdEventSink sink) {
        this.signer = new TrackingSigner(props.hmacSecret());
        this.stats = stats;
        this.eventLog = eventLog;
        this.sink = sink;
    }

    private void emit(String type, TrackingSigner.Ticket t, long spend, String flag) {
        try {
            sink.publish(AdEvent.of(type, t, spend, flag));
        } catch (RuntimeException e) {
            // the sink must never break tracking; it counts its own failures
        }
    }

    public TrackingSigner signer() {
        return signer;
    }

    public BillingLedger ledger() {
        return ledger;
    }

    /** Fresh run: forget billed/seen ids (used by the simulator when it starts a new scenario). */
    public void reset() {
        ledger = new BillingLedger(2_000_000);
        clickSeen = new RotatingDedupe(2_000_000);
        winSeen = new RotatingDedupe(2_000_000);
        stats.reset();
    }

    private void rollWindow(long now) {
        if (now - windowStart > 60_000) {
            synchronized (this) {
                if (now - windowStart > 60_000) {
                    userClicks.clear();
                    ipClicks.clear();
                    windowStart = now;
                }
            }
        }
    }

    public Outcome handle(String type, String d, String s, String ip, String userAgent) {
        long now = System.currentTimeMillis();
        TrackingSigner.Verified v = signer.verify(type, d, s, now);
        if (!v.ok()) {
            Outcome o = switch (v.fail()) {
                case BAD_SIGNATURE -> Outcome.REJECTED_SIGNATURE;
                case EXPIRED -> Outcome.REJECTED_EXPIRED;
                default -> Outcome.REJECTED_MALFORMED;
            };
            stats.reject(o.name());
            return o;
        }
        TrackingSigner.Ticket t = v.ticket();
        String id = t.eventId();
        EventStats.Counters c = stats.of(t.campaign());

        if (userAgent != null && BOT.matcher(userAgent).find()) {
            stats.flag(type, "BOT_UA", t.campaign(), ip);
                emit(type, t, 0, "BOT_UA");
            return Outcome.FLAGGED_BOT;
        }
        switch (type) {
            case "win" -> {
                if (!winSeen.addIfAbsent(id)) {
                    stats.flag(type, "DUPLICATE", t.campaign(), ip);
                emit(type, t, 0, "DUPLICATE");
                    return Outcome.DUPLICATE;
                }
                stats.wins.increment();
                emit("win", t, 0, "");
                return Outcome.ACCEPTED;
            }
            case "imp" -> {
                long amount = Math.max(1, t.priceCpmMicros() / 1000);
                if (!ledger.billImpression(id, t.campaign(), t.arm(), amount)) {
                    stats.flag(type, "DUPLICATE", t.campaign(), ip);
                emit(type, t, 0, "DUPLICATE");
                    return Outcome.DUPLICATE;
                }
                c.imps.increment();
                c.predCtrMicros.add((long) (t.pctr() * 1_000_000));
                eventLog.impression(t);
                emit("imp", t, amount, "");
                return Outcome.ACCEPTED;
            }
            case "click" -> {
                if (!ledger.hasImpression(id)) {
                    stats.flag(type, "CLICK_WITHOUT_IMPRESSION", t.campaign(), ip);
                emit(type, t, 0, "CLICK_WITHOUT_IMPRESSION");
                    return Outcome.FLAGGED_NO_IMPRESSION;
                }
                if (!clickSeen.addIfAbsent(id)) {
                    stats.flag(type, "DUPLICATE", t.campaign(), ip);
                emit(type, t, 0, "DUPLICATE");
                    return Outcome.DUPLICATE;
                }
                rollWindow(now);
                int u = userClicks.computeIfAbsent(t.user(), k -> new AtomicInteger()).incrementAndGet();
                int i = ipClicks.computeIfAbsent(ip == null ? "?" : ip, k -> new AtomicInteger()).incrementAndGet();
                if (u > MAX_CLICKS_PER_USER_PER_MIN || i > MAX_CLICKS_PER_IP_PER_MIN) {
                    stats.flag(type, "CLICK_FLOOD", t.campaign(), ip);
                emit(type, t, 0, "CLICK_FLOOD");
                    return Outcome.FLAGGED_CLICK_FLOOD;
                }
                c.clicks.increment();
                eventLog.click(t);
                emit("click", t, 0, "");
                return Outcome.ACCEPTED;
            }
            default -> {
                stats.reject("UNKNOWN_TYPE");
                return Outcome.REJECTED_MALFORMED;
            }
        }
    }
}
