package io.adexchange.freqcap;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TieredFrequencyCapTest {
    static final long H = 3_600_000L;

    /** Counts remote calls and answers exactly from its own in-memory truth. */
    static final class CountingRemote implements FrequencyCapStore {
        final InMemoryFrequencyCapStore truth = new InMemoryFrequencyCapStore(24 * H, H);
        final AtomicInteger checks = new AtomicInteger(), candidates = new AtomicInteger();
        boolean down;

        public boolean check(String u, String[] ids, int[] caps, int n, boolean[] out, long now) {
            checks.incrementAndGet();
            candidates.addAndGet(n);
            if (down) {
                for (int i = 0; i < n; i++) out[i] = false;
                return false;
            }
            return truth.check(u, ids, caps, n, out, now);
        }

        public void record(String u, String c, long now) {
            truth.record(u, c, now);
        }
    }

    @Property(tries = 300)
    void sketchNeverUnderCounts(@ForAll @IntRange(min = 1, max = 400) int events, @ForAll long seed) {
        var s = new CountMinSketch(4, 1 << 10, 4, H);        // deliberately small so collisions are common
        var rnd = new SplittableRandom(seed);
        Map<String, Integer> truth = new HashMap<>();
        for (int i = 0; i < events; i++) {
            String u = "u" + rnd.nextInt(60), c = "c" + rnd.nextInt(5);
            s.add(u, c, 10 * H);
            truth.merge(u + "|" + c, 1, Integer::sum);
        }
        truth.forEach((k, n) -> {
            String[] p = k.split("\\|");
            assertThat(s.estimate(p[0], p[1], 10 * H)).as(k).isGreaterThanOrEqualTo(n);
        });
    }

    @Test
    void impressionsOutsideTheWindowStopCounting() {
        var s = new CountMinSketch(4, 1 << 12, 24, H);
        for (int i = 0; i < 5; i++) s.add("u", "c", 100 * H);
        assertThat(s.estimate("u", "c", 100 * H)).isEqualTo(5);
        assertThat(s.estimate("u", "c", 123 * H)).as("still inside the 24 h window").isEqualTo(5);
        assertThat(s.estimate("u", "c", 124 * H)).as("bucket 100 aged out").isZero();
    }

    @Test
    void usersWellBelowTheCapNeverTouchTheRemoteStore() {
        var remote = new CountingRemote();
        var tier = new TieredFrequencyCap(remote, 4, 1 << 16, 24, H);
        boolean[] out = new boolean[2];
        tier.record("u1", "a", 50 * H);
        assertThat(tier.check("u1", new String[]{"a", "b"}, new int[]{3, 3}, 2, out, 50 * H)).isTrue();
        assertThat(out).containsExactly(false, false);
        assertThat(remote.checks.get()).as("answered entirely from the sketch").isZero();
        assertThat(tier.answeredLocally.get()).isEqualTo(2);
    }

    @Test
    void onlyCandidatesAtTheCapGoRemoteAndTheRemoteAnswerIsAuthoritative() {
        var remote = new CountingRemote();
        var tier = new TieredFrequencyCap(remote, 4, 1 << 16, 24, H);
        for (int i = 0; i < 3; i++) tier.record("u1", "a", 50 * H);   // at the cap of 3
        tier.record("u1", "b", 50 * H);                                // far below its cap
        boolean[] out = new boolean[3];
        tier.check("u1", new String[]{"a", "b", "free"}, new int[]{3, 3, 0}, 3, out, 50 * H);
        assertThat(out).containsExactly(true, false, false);
        assertThat(remote.checks.get()).isEqualTo(1);
        assertThat(remote.candidates.get()).as("only 'a' was sent").isEqualTo(1);
    }

    @Test
    void whenTheRemoteIsDownCandidatesFailOpenAndTheResultIsFlaggedDegraded() {
        var remote = new CountingRemote();
        var tier = new TieredFrequencyCap(remote, 4, 1 << 16, 24, H);
        for (int i = 0; i < 3; i++) tier.record("u1", "a", 50 * H);
        remote.down = true;
        boolean[] out = new boolean[1];
        assertThat(tier.check("u1", new String[]{"a"}, new int[]{3}, 1, out, 50 * H)).as("degraded").isFalse();
        assertThat(out[0]).as("fails open").isFalse();
    }

    /** Documents the memory/accuracy trade-off with measured numbers (printed; asserted loosely). */
    @Test
    void measuredAccuracyAndRemoteLoadReduction() {
        var rnd = new SplittableRandom(42);
        int users = 200_000, campaigns = 20, cap = 3;
        for (int width : new int[]{1 << 14, 1 << 16, 1 << 18}) {
            var remote = new CountingRemote();
            var tier = new TieredFrequencyCap(remote, 4, width, 24, H);
            // each request: one user sees up to 8 candidates; impressions are recorded for the winner only
            int requests = 400_000, falseRemote = 0, trulyCapped = 0, wrongAnswers = 0;
            String[] ids = new String[8];
            int[] caps = new int[8];
            boolean[] out = new boolean[8], exact = new boolean[8];
            for (int r = 0; r < requests; r++) {
                String u = "u" + (int) Math.min(users - 1, Math.pow(users, rnd.nextDouble()));   // Zipf-like: a few heavy users
                for (int k = 0; k < 8; k++) { ids[k] = "c" + ((r + k * 7) % campaigns); caps[k] = cap; }
                tier.check(u, ids, caps, 8, out, 50 * H);
                remote.truth.check(u, ids, caps, 8, exact, 50 * H);
                for (int k = 0; k < 8; k++) {
                    if (exact[k]) trulyCapped++;
                    if (out[k] != exact[k]) wrongAnswers++;
                }
                tier.record(u, ids[r % 8], 50 * H);
            }
            long total = tier.answeredLocally.get() + tier.sentToRemote.get();
            System.out.printf("count-min width=%-7d memory=%5.1f MiB  asked-remote=%5.1f%% of cap checks  truly-capped=%d  wrong answers=%d%n",
                    width, tier.sketchMemoryBytes() / 1048576.0, 100.0 * tier.sentToRemote.get() / total, trulyCapped, wrongAnswers);
            assertThat(wrongAnswers).as("the remote answer is authoritative, so the tier never changes an answer on one instance").isZero();
        }
    }
}
