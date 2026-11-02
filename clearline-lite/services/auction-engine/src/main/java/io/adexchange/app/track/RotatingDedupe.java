package io.adexchange.app.track;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded "have I seen this id" set using two generations. Memory is capped at roughly 2 x maxPerGeneration;
 * an id is remembered for at least maxPerGeneration subsequent inserts. {@link #addIfAbsent} is atomic per id.
 */
public final class RotatingDedupe {
    private final int maxPerGen;
    private volatile Set<String> cur = ConcurrentHashMap.newKeySet();
    private volatile Set<String> prev = ConcurrentHashMap.newKeySet();

    public RotatingDedupe(int maxPerGeneration) {
        this.maxPerGen = maxPerGeneration;
    }

    /** @return true if the id was new (caller should process the event), false if it is a duplicate */
    public boolean addIfAbsent(String id) {
        if (prev.contains(id)) return false;
        boolean added = cur.add(id);
        if (added && cur.size() > maxPerGen) rotate();
        return added;
    }

    public boolean contains(String id) {
        return cur.contains(id) || prev.contains(id);
    }

    private synchronized void rotate() {
        if (cur.size() > maxPerGen) {
            prev = cur;
            cur = ConcurrentHashMap.newKeySet();
        }
    }
}
