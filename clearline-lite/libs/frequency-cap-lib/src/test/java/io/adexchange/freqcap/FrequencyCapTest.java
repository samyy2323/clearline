package io.adexchange.freqcap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrequencyCapTest {
    static final long H = 3_600_000L;

    @Test
    void capsAfterNImpressionsAndAgesOutOfWindow() {
        var s = new InMemoryFrequencyCapStore(24 * H, H);
        String[] ids = {"a", "b"};
        int[] caps = {2, 0};
        boolean[] out = new boolean[2];
        long t = 100 * H;
        s.record("u", "a", t);
        s.check("u", ids, caps, 2, out, t);
        assertThat(out).containsExactly(false, false);
        s.record("u", "a", t + 1);
        s.record("u", "b", t + 1);
        s.check("u", ids, caps, 2, out, t + 2);
        assertThat(out).as("a capped, b uncapped").containsExactly(true, false);
        s.check("u", ids, caps, 2, out, t + 25 * H);
        assertThat(out).as("window slid past").containsExactly(false, false);
        s.check("other", ids, caps, 2, out, t + 2);
        assertThat(out).containsExactly(false, false);
    }

    @Test
    void slowStoreOpensTheBreakerSoAuctionsStopPayingForIt() {
        FrequencyCapStore slow = new FrequencyCapStore() {
            public boolean check(String u, String[] i, int[] c, int n, boolean[] o, long t) {
                try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return true;
            }
            public void record(String u, String c, long t) {}
        };
        try (var r = new ResilientFrequencyCap(slow, 4, java.time.Duration.ofMillis(10), java.time.Duration.ofSeconds(30))) {
            boolean[] out = new boolean[1];
            for (int i = 0; i < 12; i++) r.check("u", new String[]{"a"}, new int[]{1}, 1, out, 0);
            assertThat(r.breakerState()).isEqualTo("OPEN");
            long t0 = System.nanoTime();
            boolean ok = r.check("u", new String[]{"a"}, new int[]{1}, 1, out, 0);
            assertThat(ok).as("skipped, flagged degraded").isFalse();
            assertThat((System.nanoTime() - t0) / 1_000_000).as("returns immediately").isLessThan(5);
            assertThat(r.breakerRejected.get()).isPositive();
        }
    }

    @Test
    void failsOpenAndFlagsDegradedWhenStoreThrows() {
        FrequencyCapStore broken = new FrequencyCapStore() {
            public boolean check(String u, String[] i, int[] c, int n, boolean[] o, long t) { throw new IllegalStateException(); }
            public void record(String u, String c, long t) { throw new IllegalStateException(); }
        };
        try (var r = new ResilientFrequencyCap(broken, 4)) {
            boolean[] out = {true};
            assertThat(r.check("u", new String[]{"a"}, new int[]{1}, 1, out, 0)).isFalse();
            assertThat(out[0]).isFalse();
            assertThat(r.degradedChecks.get()).isEqualTo(1);
        }
    }
}
