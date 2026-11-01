package io.adexchange.app.campaign;

import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.Campaign;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * System of record for campaigns plus a transactional outbox. A write updates the table and appends a
 * change event under one lock (the "transaction"); {@link #relay()} later publishes events to the index.
 * In full mode the same outbox rows go to Kafka and the engine consumes them; lite mode relays in-process.
 */
@Service
public class CampaignService {

    public record Event(long seq, String type, String campaignId, Campaign payload) {}

    private final Map<String, Campaign> table = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Event> outbox = new ConcurrentLinkedQueue<>();
    private final AtomicLong seq = new AtomicLong();
    private final IndexHolder index;
    public final AtomicLong relayed = new AtomicLong();
    private volatile long lastRelayLatencyMs;

    public CampaignService(IndexHolder index) {
        this.index = index;
    }

    public synchronized Campaign save(Campaign c) {
        table.put(c.id(), c);
        outbox.add(new Event(seq.incrementAndGet(), "UPSERT", c.id(), c));
        return c;
    }

    public synchronized boolean delete(String id) {
        if (table.remove(id) == null) return false;
        outbox.add(new Event(seq.incrementAndGet(), "DELETE", id, null));
        return true;
    }

    public Campaign get(String id) {
        return table.get(id);
    }

    public Collection<Campaign> all() {
        return table.values();
    }

    public List<Campaign> forAdvertiser(String adv) {
        List<Campaign> out = new ArrayList<>();
        for (Campaign c : table.values()) if (c.advertiserId().equals(adv)) out.add(c);
        return out;
    }

    /** Bulk load that bypasses the outbox (startup / simulator reset). */
    public synchronized void replaceAll(Collection<Campaign> cs) {
        table.clear();
        outbox.clear();
        cs.forEach(c -> table.put(c.id(), c));
        index.replaceAll(new HashSet<>(cs));
    }

    @Scheduled(fixedDelay = 100)
    public void relay() {
        long t0 = System.currentTimeMillis();
        Event e;
        boolean any = false;
        while ((e = outbox.poll()) != null) {
            if ("UPSERT".equals(e.type())) index.upsert(e.payload());
            else index.remove(e.campaignId());
            relayed.incrementAndGet();
            any = true;
        }
        if (any) lastRelayLatencyMs = System.currentTimeMillis() - t0;
    }

    public int outboxDepth() {
        return outbox.size();
    }

    public long lastRelayLatencyMs() {
        return lastRelayLatencyMs;
    }
}
