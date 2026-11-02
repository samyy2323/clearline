package io.adexchange.app.track;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** In-memory aggregates standing in for ClickHouse rollups in lite mode. */
@Component
public class EventStats {

    public static final class Counters {
        public final LongAdder awards = new LongAdder();
        public final LongAdder awardedMicros = new LongAdder(); // booked at auction time
        public final LongAdder imps = new LongAdder();
        public final LongAdder clicks = new LongAdder();
        public final LongAdder predCtrMicros = new LongAdder(); // sum of predicted CTR x 1e6 over impressions
    }

    public record Flag(long ts, String type, String reason, String campaign, String ip) {}

    private final Map<String, Counters> byCampaign = new ConcurrentHashMap<>();
    public final Map<String, LongAdder> flagged = new ConcurrentHashMap<>();
    public final Map<String, LongAdder> rejected = new ConcurrentHashMap<>();
    public final LongAdder wins = new LongAdder();
    private final ArrayDeque<Flag> recentFlags = new ArrayDeque<>();

    public Counters of(String campaignId) {
        return byCampaign.computeIfAbsent(campaignId, k -> new Counters());
    }

    public Map<String, Counters> all() {
        return byCampaign;
    }

    public void flag(String type, String reason, String campaign, String ip) {
        flagged.computeIfAbsent(reason, k -> new LongAdder()).increment();
        synchronized (recentFlags) {
            if (recentFlags.size() >= 200) recentFlags.pollFirst();
            recentFlags.addLast(new Flag(System.currentTimeMillis(), type, reason, campaign, ip));
        }
    }

    public void reject(String reason) {
        rejected.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    public List<Flag> recentFlags() {
        synchronized (recentFlags) {
            return new ArrayList<>(recentFlags);
        }
    }

    public long totalFlagged() {
        return flagged.values().stream().mapToLong(LongAdder::sum).sum();
    }

    public void reset() {
        byCampaign.clear();
        flagged.clear();
        rejected.clear();
        wins.reset();
        synchronized (recentFlags) {
            recentFlags.clear();
        }
    }
}
