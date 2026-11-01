package io.adexchange.budget;

import io.adexchange.clock.AdClock;

import java.util.function.DoubleUnaryOperator;

/**
 * Spreads a campaign's budget over its flight. A velocity-form PI controller adjusts the probability
 * that the campaign participates in an auction; the error is (target spend fraction - actual spend fraction).
 * Output is clamped to [0,1], which doubles as anti-windup.
 */
public final class PacingController {

    private final AdClock clock;
    private final long startMs;
    private final long endMs;
    private final long budgetMicros;
    private final double kp;
    private final double ki;
    private final DoubleUnaryOperator shape;

    private volatile double participation = 1.0;
    private double prevError;
    private volatile double lastError;

    public PacingController(AdClock clock, long startMs, long endMs, long budgetMicros, double kp, double ki) {
        this(clock, startMs, endMs, budgetMicros, kp, ki, p -> p);
    }

    /** {@code shape} maps flight progress [0,1] to the cumulative spend fraction that should be reached by then. */
    public PacingController(AdClock clock, long startMs, long endMs, long budgetMicros,
                            double kp, double ki, DoubleUnaryOperator shape) {
        this.clock = clock;
        this.startMs = startMs;
        this.endMs = endMs;
        this.budgetMicros = budgetMicros;
        this.kp = kp;
        this.ki = ki;
        this.shape = shape;
    }

    public double targetFraction() {
        long now = clock.nowMillis();
        if (now <= startMs) return 0;
        if (now >= endMs) return 1;
        return Math.min(1, Math.max(0, shape.applyAsDouble((now - startMs) / (double) (endMs - startMs))));
    }

    /** Call periodically (e.g. once a second) with cumulative spend. */
    public void update(long spentMicros) {
        double actual = budgetMicros == 0 ? 1 : spentMicros / (double) budgetMicros;
        double e = targetFraction() - actual;
        double p = participation + kp * (e - prevError) + ki * e;
        participation = Math.min(1.0, Math.max(0.0, p));
        prevError = e;
        lastError = e;
    }

    public double participation() {
        long now = clock.nowMillis();
        return (now < startMs || now >= endMs) ? 0 : participation;
    }

    /** @param rand01 uniform random in [0,1) */
    public boolean admit(double rand01) {
        return rand01 < participation();
    }

    /** Signed pacing error as a fraction of budget (target - actual). Exposed as a metric. */
    public double error() {
        return lastError;
    }
}
