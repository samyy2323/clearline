package io.adexchange.freqcap;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a (possibly remote) store: reads fail open and flag {@code degraded}; writes go through a bounded
 * queue drained by a background thread and are dropped (counted) rather than block the auction.
 */
public final class ResilientFrequencyCap implements FrequencyCapStore, AutoCloseable {

    private record Rec(String user, String campaign, long now) {}

    private final FrequencyCapStore delegate;
    private final BlockingQueue<Rec> queue;
    private final Thread drainer;
    private volatile boolean running = true;

    public final AtomicLong degradedChecks = new AtomicLong();
    public final AtomicLong droppedWrites = new AtomicLong();

    public ResilientFrequencyCap(FrequencyCapStore delegate, int queueCapacity) {
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.drainer = Thread.ofPlatform().daemon().name("freqcap-writer").start(() -> {
            while (running) {
                try {
                    Rec r = queue.take();
                    delegate.record(r.user, r.campaign, r.now);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    droppedWrites.incrementAndGet();
                }
            }
        });
    }

    @Override
    public boolean check(String userId, String[] ids, int[] caps, int n, boolean[] capped, long now) {
        boolean ok;
        try {
            ok = delegate.check(userId, ids, caps, n, capped, now);
        } catch (RuntimeException e) {
            for (int i = 0; i < n; i++) capped[i] = false;
            ok = false;
        }
        if (!ok) degradedChecks.incrementAndGet();
        return ok;
    }

    @Override
    public void record(String userId, String campaignId, long now) {
        if (!queue.offer(new Rec(userId, campaignId, now))) droppedWrites.incrementAndGet();
    }

    @Override
    public void close() {
        running = false;
        drainer.interrupt();
    }
}
