package io.adexchange.clock;

/** Injectable time source so pacing and leases can run in a compressed "day" for demos and tests. */
public interface AdClock {

    long nowMillis();

    /** Fraction [0,1) of the current simulated day elapsed (UTC-based). */
    default double dayFraction() {
        long dayMs = 86_400_000L;
        return (nowMillis() % dayMs) / (double) dayMs;
    }

    /** Hour of simulated day, 0..23. */
    default int hourOfDay() {
        return (int) ((nowMillis() / 3_600_000L) % 24);
    }

    static AdClock system() {
        return System::currentTimeMillis;
    }
}
