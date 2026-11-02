package io.adexchange.auction.index;

import io.adexchange.auction.model.Campaign;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Holds the live index snapshot and applies campaign-change events (from Kafka, or the in-process bus in lite mode). */
public final class IndexHolder {

    private final Map<String, Campaign> all = new ConcurrentHashMap<>();
    private volatile CampaignIndex snapshot = CampaignIndex.build(java.util.List.of());
    private final AtomicLong version = new AtomicLong();
    private volatile Consumer<Campaign> onUpsert = c -> {};
    private volatile Consumer<String> onRemove = id -> {};

    public CampaignIndex current() {
        return snapshot;
    }

    public long version() {
        return version.get();
    }

    public Campaign get(String id) {
        return all.get(id);
    }

    public Collection<Campaign> all() {
        return all.values();
    }

    /** Hooks so budget/pacing can follow campaign lifecycle. */
    public void listen(Consumer<Campaign> upsert, Consumer<String> remove) {
        this.onUpsert = upsert;
        this.onRemove = remove;
    }

    public synchronized void upsert(Campaign c) {
        all.put(c.id(), c);
        rebuild();
        onUpsert.accept(c);
    }

    public synchronized void remove(String id) {
        if (all.remove(id) != null) {
            rebuild();
            onRemove.accept(id);
        }
    }

    /** Applies many changes with ONE snapshot rebuild (consumer catch-up, bulk loads). */
    public synchronized void applyBatch(Collection<Campaign> upserts, Collection<String> removes) {
        removes.forEach(all::remove);
        upserts.forEach(c -> all.put(c.id(), c));
        rebuild();
        removes.forEach(onRemove);
        upserts.forEach(onUpsert);
    }

    public synchronized void replaceAll(Collection<Campaign> cs) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        cs.forEach(c -> keep.add(c.id()));
        java.util.List<String> gone = all.keySet().stream().filter(id -> !keep.contains(id)).toList();
        all.clear();
        cs.forEach(c -> all.put(c.id(), c));
        rebuild();
        gone.forEach(onRemove);
        cs.forEach(onUpsert);
    }

    private void rebuild() {
        snapshot = CampaignIndex.build(all.values());
        version.incrementAndGet();
    }
}
