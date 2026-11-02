package io.adexchange.app.track;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-signed, expiring tracking tokens. The signature covers the event type, so a win/impression token can't
 * be replayed as a click. Tokens are bearer capabilities: forging one needs the secret, replaying one is
 * absorbed by event-id dedupe.
 */
public final class TrackingSigner {

    public record Ticket(String reqId, String slot, String campaign, String advertiser, long priceCpmMicros,
                         String arm, String user, String pub, String cat, String dev, String geo, int hour,
                         String segs, double pctr, long expMs) {
        public String eventId() {
            return reqId + "|" + slot + "|" + campaign;
        }
    }

    public enum Fail { MALFORMED, BAD_SIGNATURE, EXPIRED }

    public record Verified(Ticket ticket, Fail fail) {
        public boolean ok() {
            return ticket != null;
        }
    }

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private final byte[] key;
    // Mac.getInstance + init per call was ~19% of allocation; a ThreadLocal would not help (virtual threads), so pool instances
    private final java.util.concurrent.ConcurrentLinkedQueue<Mac> pool = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public TrackingSigner(String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    private Mac borrow() throws Exception {
        Mac m = pool.poll();
        if (m != null) return m;
        m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('|', '_');
    }

    private String mac(String type, String data) {
        Mac m = null;
        try {
            m = borrow();
            m.update(type.getBytes(StandardCharsets.US_ASCII));
            m.update((byte) '.');
            return ENC.encodeToString(m.doFinal(data.getBytes(StandardCharsets.US_ASCII))); // doFinal resets the Mac
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (m != null) pool.offer(m);
        }
    }

    private static String encode(Ticket t) {
        String raw = String.join("|", clean(t.reqId), clean(t.slot), clean(t.campaign), clean(t.advertiser),
                Long.toString(t.priceCpmMicros), clean(t.arm), clean(t.user), clean(t.pub), clean(t.cat), clean(t.dev),
                clean(t.geo), Integer.toString(t.hour), clean(t.segs), Double.toString(t.pctr), Long.toString(t.expMs));
        return ENC.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return query string "d=...&s=..." */
    public String sign(String type, Ticket t) {
        String d = encode(t);
        return "d=" + d + "&s=" + mac(type, d);
    }

    /** win, imp and click query strings for one ticket; the payload is encoded once and only the MAC differs. */
    public String[] signAll(Ticket t) {
        String d = encode(t);
        return new String[]{"d=" + d + "&s=" + mac("win", d), "d=" + d + "&s=" + mac("imp", d), "d=" + d + "&s=" + mac("click", d)};
    }

    public Verified verify(String type, String d, String s, long nowMs) {
        if (d == null || s == null || d.length() > 2048 || s.length() > 128) return new Verified(null, Fail.MALFORMED);
        byte[] expected = mac(type, d).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII))) return new Verified(null, Fail.BAD_SIGNATURE);
        try {
            String[] p = new String(DEC.decode(d), StandardCharsets.UTF_8).split("\\|", -1);
            if (p.length != 15) return new Verified(null, Fail.MALFORMED);
            Ticket t = new Ticket(p[0], p[1], p[2], p[3], Long.parseLong(p[4]), p[5], p[6], p[7], p[8], p[9], p[10],
                    Integer.parseInt(p[11]), p[12], Double.parseDouble(p[13]), Long.parseLong(p[14]));
            if (t.expMs < nowMs) return new Verified(null, Fail.EXPIRED);
            return new Verified(t, null);
        } catch (RuntimeException e) {
            return new Verified(null, Fail.MALFORMED);
        }
    }
}
