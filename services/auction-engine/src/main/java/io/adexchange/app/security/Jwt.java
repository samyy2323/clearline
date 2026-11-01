package io.adexchange.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/** Minimal HS256 JWT (no external dependency): sub, role (admin|advertiser), adv (advertiser id), exp. */
public final class Jwt {
    public record Claims(String sub, String role, String advertiserId, long expEpochSec) {
        public boolean admin() {
            return "admin".equals(role);
        }
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] key;

    public Jwt(String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    private String mac(String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return ENC.encodeToString(m.doFinal(data.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String issue(String sub, String role, String advertiserId, long ttlSec) {
        try {
            long exp = System.currentTimeMillis() / 1000 + ttlSec;
            String payload = ENC.encodeToString(M.writeValueAsBytes(
                    Map.of("sub", sub, "role", role, "adv", advertiserId == null ? "" : advertiserId, "exp", exp)));
            String body = HEADER + "." + payload;
            return body + "." + mac(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return claims or null if malformed, wrongly signed or expired */
    public Claims verify(String token) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 3 || !p[0].equals(HEADER)) return null;
            byte[] expected = mac(p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, p[2].getBytes(StandardCharsets.US_ASCII))) return null;
            JsonNode n = M.readTree(DEC.decode(p[1]));
            long exp = n.get("exp").asLong();
            if (exp < System.currentTimeMillis() / 1000) return null;
            return new Claims(n.get("sub").asText(), n.get("role").asText(), n.path("adv").asText(""), exp);
        } catch (Exception e) {
            return null;
        }
    }
}
