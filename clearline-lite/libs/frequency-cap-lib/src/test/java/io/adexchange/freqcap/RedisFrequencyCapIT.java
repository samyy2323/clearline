package io.adexchange.freqcap;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisFrequencyCapIT {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Test
    void batchCheckInOneCallMatchesInMemorySemantics() {
        long h = 3_600_000L, t = 50 * h;
        try (var s = new RedisFrequencyCapStore("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379), 24 * h, h)) {
            String[] ids = {"a", "b", "c"};
            int[] caps = {1, 3, 0};
            boolean[] out = new boolean[3];
            s.record("u1", "a", t);
            s.record("u1", "b", t);
            s.record("u1", "c", t);
            assertThat(s.check("u1", ids, caps, 3, out, t + 1)).isTrue();
            assertThat(out).containsExactly(true, false, false);
            assertThat(s.check("u1", ids, caps, 3, out, t + 30 * h)).isTrue();
            assertThat(out).as("aged out").containsExactly(false, false, false);
        }
    }
}
