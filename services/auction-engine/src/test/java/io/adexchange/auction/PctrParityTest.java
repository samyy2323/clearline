package io.adexchange.auction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adexchange.auction.scoring.PctrModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Training (Python) and serving (Java) must hash identically or the exported weights are meaningless. */
class PctrParityTest {

    @Test
    void javaHashingMatchesPythonVectors() throws Exception {
        JsonNode v = new ObjectMapper().readTree(Files.readAllBytes(Path.of("../../ml/parity_vectors.json")));
        for (JsonNode e : v.get("fnv1a")) {
            assertThat(PctrModel.fnv1a(e.get("s").asText())).as(e.get("s").asText()).isEqualTo(e.get("h").asInt());
        }
        for (JsonNode e : v.get("index")) {
            assertThat(PctrModel.index(e.get("f").asInt(), e.get("c").asInt())).isEqualTo(e.get("idx").asInt());
        }
    }
}
