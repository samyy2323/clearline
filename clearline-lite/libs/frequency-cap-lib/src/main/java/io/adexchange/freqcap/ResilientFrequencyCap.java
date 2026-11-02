package io.adexchange.freqcap;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a (possibly remote) store so it can never drag the auction down with it:
 * <ul>
 *   <li>reads fail open and flag {@code degraded};</li>
 *   <li>a Resilience4j circuit breaker treats errors AND slow calls (&gt; 10 ms) as failures; once it opens, lookups are
 *       skipped (fail open, degraded) for 2 s instead of every auction paying the slow call;</li>
 *   <li>writes go through a bounded queue drained by a background thread and are dropped (counted) rather than block.</li>
 * </ul>
 */
public final class ResilientFrequencyCap implements FrequencyCapStore, AutoCloseable {

    private record Rec(String user, String campaign, long now) {}

    private final FrequencyCapStore delegate;
    private final BlockingQueue<Rec> queue;
    private final Thread drainer;
    private final CircuitBreaker breaker;
    private volatile boolean running = true;

    public final AtomicLong degradedChecks = new AtomicLong();
    public final AtomicLong breakerRejected = new AtomicLong();
    public final AtomicLong droppedWrites = new AtomicLong();

    public ResilientFrequencyCap(FrequencyCapStore delegate, int queueCapacity) {
        this(delegate, queueCapacity, Duration.ofMillis(10), Duration.ofSeconds(2));
    }

    public ResilientFrequencyCap(FrequencyCapStore delegate, int queueCapacity, Duration slowCall, Duration openFor) {
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.breaker = CircuitBreaker.of("frequency-cap", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(slowCall)
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(5)
                .build());
        this.drainer = Thread.ofPlatform().daemon().name("freqcap-writer").start(() -> {
            while (running) {
                try {
                    Rec r = queue.take();
                    if (breaker.getState() == CircuitBreaker.State.OPEN) {
                        droppedWrites.incrementAndGet(); // do not pile writes onto a store we already know is sick
                        continue;
                    }
                    delegate.record(r.user, r.campaign, r.now);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    droppedWrites.incrementAndGet();
                }
            }
        });
    }

    public String breakerState() {
        return breaker.getState().name();
    }

    private void failOpen(int n, boolean[] capped) {
        for (int i = 0; i < n; i++) capped[i] = false;
    }

    @Override
    public boolean check(String userId, String[] ids, int[] caps, int n, boolean[] capped, long now) {
        if (!breaker.tryAcquirePermission()) {
            breakerRejected.incrementAndGet();
            degradedChecks.incrementAndGet();
            failOpen(n, capped);
            return false;
        }
        long t0 = System.nanoTime();
        boolean ok;
        try {
            ok = delegate.check(userId, ids, caps, n, capped, now);
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - t0, TimeUnit.NANOSECONDS, e);
            failOpen(n, capped);
            degradedChecks.incrementAndGet();
            return false;
        }
        long took = System.nanoTime() - t0;
        if (ok) {
            breaker.onSuccess(took, TimeUnit.NANOSECONDS);
        } else {
            breaker.onError(took, TimeUnit.NANOSECONDS, new IllegalStateException("store reported degraded"));
            degradedChecks.incrementAndGet();
        }
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
