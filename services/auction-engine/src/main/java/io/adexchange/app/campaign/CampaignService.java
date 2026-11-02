package io.adexchange.app.campaign;

import io.adexchange.auction.model.Campaign;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Campaign operations; persistence and event delivery are the {@link CampaignStore}'s business (ADR 0004). */
@Service
public class CampaignService {

    private final CampaignStore store;
    public final AtomicLong relayed = new AtomicLong();
    public final AtomicLong relayFailures = new AtomicLong();
    private volatile long lastRelayLatencyMs;

    public CampaignService(CampaignStore store) {
        this.store = store;
    }

    public Campaign save(Campaign c) {
        return store.save(c);
    }

    public boolean delete(String id) {
        return store.delete(id);
    }

    public Campaign get(String id) {
        return store.get(id);
    }

    public Collection<Campaign> all() {
        return store.all();
    }

    public List<Campaign> forAdvertiser(String adv) {
        return store.forAdvertiser(adv);
    }

    public void replaceAll(Collection<Campaign> cs) {
        store.replaceAll(cs);
    }

    @Scheduled(fixedDelay = 100)
    public void relay() {
        long t0 = System.currentTimeMillis();
        try {
            int n = store.relay();
            if (n > 0) {
                relayed.addAndGet(n);
                lastRelayLatencyMs = System.currentTimeMillis() - t0;
            }
        } catch (RuntimeException e) {
            relayFailures.incrementAndGet(); // e.g. Kafka or Postgres briefly down: events stay in the outbox and are retried
        }
    }

    public int outboxDepth() {
        try {
            return store.outboxDepth();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public long lastRelayLatencyMs() {
        return lastRelayLatencyMs;
    }
}
