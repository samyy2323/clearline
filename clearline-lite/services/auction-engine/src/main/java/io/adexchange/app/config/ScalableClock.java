package io.adexchange.app.config;

import io.adexchange.clock.AdClock;

/** Simulated clock whose speed can change at runtime ("compressed day": 72x turns 24h into 20 min). */
public final class ScalableClock implements AdClock {
    private record State(long realBase, long simBase, double speed) {}

    private volatile State s = new State(System.currentTimeMillis(), System.currentTimeMillis(), 1.0);

    @Override
    public long nowMillis() {
        State c = s;
        return c.simBase + (long) ((System.currentTimeMillis() - c.realBase) * c.speed);
    }

    public double speed() {
        return s.speed;
    }

    /** Rebases the clock; pass {@code simStartMillis < 0} to keep the current simulated instant. */
    public synchronized void set(double speed, long simStartMillis) {
        long now = System.currentTimeMillis();
        long sim = simStartMillis >= 0 ? simStartMillis : nowMillis();
        s = new State(now, sim, speed);
    }
}
