package io.adexchange.budget;

import io.adexchange.clock.ManualClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PacingControllerTest {

    /** 24h flight, 1 min ticks, demand is 3x what an even spread needs (a campaign that would burn out by 8am). */
    @Test
    void compressedDayTracksLinearTargetWithinTolerance() {
        long dayMs = 86_400_000L, budget = 1_000_000;
        ManualClock clock = new ManualClock(0);
        PacingController pc = new PacingController(clock, 0, dayMs, budget, 1.0, 0.5);
        double spent = 0, maxErr = 0;
        long demandPerTick = 3 * budget / 1440;
        for (int tick = 0; tick < 1440; tick++) {
            clock.advance(60_000);
            spent = Math.min(budget, spent + demandPerTick * pc.participation());
            pc.update((long) spent);
            if (tick > 30) maxErr = Math.max(maxErr, Math.abs(pc.error()));
        }
        assertThat(maxErr).as("max |target-actual| as fraction of budget").isLessThan(0.05);
        assertThat(spent / budget).isBetween(0.97, 1.0);
    }

    @Test
    void doesNotThrottleWhenDemandIsBelowTarget() {
        long dayMs = 86_400_000L, budget = 1_000_000;
        ManualClock clock = new ManualClock(0);
        PacingController pc = new PacingController(clock, 0, dayMs, budget, 1.0, 0.5);
        double spent = 0;
        for (int tick = 0; tick < 1440; tick++) {
            clock.advance(60_000);
            spent += 0.5 * budget / 1440 * pc.participation();
            pc.update((long) spent);
        }
        assertThat(pc.participation()).isEqualTo(0.0); // flight ended
        assertThat(spent / budget).isBetween(0.49, 0.51);
    }

    @Test
    void outsideFlightNeverParticipates() {
        ManualClock clock = new ManualClock(0);
        PacingController pc = new PacingController(clock, 1000, 2000, 100, 1, 1);
        assertThat(pc.participation()).isZero();
        clock.set(1500);
        assertThat(pc.participation()).isEqualTo(1.0);
    }
}
