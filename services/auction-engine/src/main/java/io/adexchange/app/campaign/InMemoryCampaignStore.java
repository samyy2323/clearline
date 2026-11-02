package io.adexchange.app.campaign;

import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.Campaign;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lite edition: table and outbox in memory. A write updates the table and appends a change event under one lock (standing in for
 * one database transaction); the relay later applies events, in order, to the local index.
 */
@Component
@ConditionalOnProperty(name = "exchange.mode", havingValue = "lite", matchIfMissing = true)
public class InMemoryCampaignStore implements CampaignStore {

    private record Event(String type, String campaignId, Campaign payload) {}

    private final Map<String, Campaign> table = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Event> outbox = new ConcurrentLinkedQueue<>();
    private final IndexHolder index;

    public InMemoryCampaignStore(IndexHolder index) {
        this.index = index;
    }

    @Override
    public synchronized Campaign save(Campaign c) {
        table.put(c.id(), c);
        outbox.add(new Event("UPSERT", c.id(), c));
        return c;
    }

    @Override
    public synchronized boolean delete(String id) {
        if (table.remove(id) == null) return false;
        outbox.add(new Event("DELETE", id, null));
        return true;
    }

    @Override
    public Campaign get(String id) {
        return table.get(id);
    }

    @Override
    public Collection<Campaign> all() {
        return table.values();
    }

    @Override
    public List<Campaign> forAdvertiser(String adv) {
        List<Campaign> out = new ArrayList<>();
        for (Campaign c : table.values()) if (c.advertiserId().equals(adv)) out.add(c);
        return out;
    }

    @Override
    public synchronized void replaceAll(Collection<Campaign> cs) {
        table.clear();
        outbox.clear();
        cs.forEach(c -> table.put(c.id(), c));
        index.replaceAll(new HashSet<>(cs));
    }

    @Override
    public int outboxDepth() {
        return outbox.size();
    }

    @Override
    public int relay() {
        int n = 0;
        Event e;
        while ((e = outbox.poll()) != null) {
            if ("UPSERT".equals(e.type())) index.upsert(e.payload());
            else index.remove(e.campaignId());
            n++;
        }
        return n;
    }
}
