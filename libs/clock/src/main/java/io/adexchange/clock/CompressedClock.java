package io.adexchange.clock;

/**
 * Runs simulated time faster than wall time: {@code speedup = 72} turns 24 h into 20 min.
 * Simulated time starts at {@code simStartMillis} when the clock is constructed.
 */
public final class CompressedClock implements AdClock {
    private final long realStart;
    private final long simStart;
    private final double speedup;
    private final AdClock real;

    public CompressedClock(long simStartMillis, double speedup) {
        this(AdClock.system(), simStartMillis, speedup);
    }

    public CompressedClock(AdClock real, long simStartMillis, double speedup) {
        if (speedup <= 0) throw new IllegalArgumentException("speedup must be > 0");
        this.real = real;
        this.realStart = real.nowMillis();
        this.simStart = simStartMillis;
        this.speedup = speedup;
    }

    @Override
    public long nowMillis() {
        return simStart + (long) ((real.nowMillis() - realStart) * speedup);
    }
}
