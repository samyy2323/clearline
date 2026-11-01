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

    private long lastNow = -1;
    private long lastSpent;
    private double potentialRate;   // EMA of spend per ms the campaign WOULD have at participation 1
    private double integral;

    /**
     * Call periodically with cumulative spend. Feed-forward + PI trim:
     * <ul>
     *   <li>feed-forward: participation = (remaining budget / remaining time) / estimated full-participation spend rate.
     *       This reacts to how fast money is leaving, which a cumulative-error PI alone cannot see until it is too late.</li>
     *   <li>trim: kp * e + ki * integral(e), e = target fraction - actual fraction, so drift from the curve is corrected.</li>
     * </ul>
     */
    public synchronized void update(long spentMicros) {
        long now = clock.nowMillis();
        double actual = budgetMicros == 0 ? 1 : spentMicros / (double) budgetMicros;
        double e = targetFraction() - actual;
        lastError = e;
        if (now < startMs || now >= endMs) {
            lastNow = now;
            lastSpent = spentMicros;
            return;
        }
        long dt = lastNow < 0 ? 0 : now - lastNow;
        if (dt > 0) {
            double p0 = participation;
            if (p0 > 0.02) {
                double inst = (spentMicros - lastSpent) / (double) dt / p0;
                potentialRate = potentialRate == 0 ? inst : 0.7 * potentialRate + 0.3 * inst;
            }
            double ticks = dt / ((endMs - startMs) / 1440.0);          // time in units of 1/1440 of the flight
            integral = Math.min(0.5, Math.max(-0.5, integral + e * ticks));
        }
        double desired = Math.max(0, budgetMicros - spentMicros) / (double) Math.max(1, endMs - now);
        double ff = potentialRate > 0 ? desired / potentialRate : 1.0;
        participation = Math.min(1.0, Math.max(0.0, ff + kp * e + ki * integral));
        lastNow = now;
        lastSpent = spentMicros;
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
