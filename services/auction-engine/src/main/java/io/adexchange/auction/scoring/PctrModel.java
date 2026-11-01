package io.adexchange.auction.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/**
 * In-process logistic regression over hashed features. Weights live in a flat float[]; reloading swaps one
 * reference, so scoring never blocks. Training happens offline (ml/) and must use the identical hashing:
 * <pre>
 *   featureHash = fnv1a32("name=value" low bytes)
 *   index(feature, campaign) = fmix32(featureHash ^ (campaignHash * 0x9E3779B1)) &amp; (2^18 - 1)
 * </pre>
 */
public final class PctrModel {

    public static final int BITS = 18;
    public static final int MASK = (1 << BITS) - 1;
    public static final double DEFAULT_BIAS = -3.9; // ~2% CTR prior

    private record Weights(float[] w, double bias, long version, String source) {}

    private volatile Weights weights = new Weights(new float[1 << BITS], DEFAULT_BIAS, 0, "stub");
    private long lastModified = -1;

    public static int fnv1a(String s) {
        int h = 0x811C9DC5;
        for (int i = 0, n = s.length(); i < n; i++) {
            h ^= (s.charAt(i) & 0xff);
            h *= 0x01000193;
        }
        return h;
    }

    public static int fmix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    public static int index(int featureHash, int campaignHash) {
        return fmix(featureHash ^ (campaignHash * 0x9E3779B1)) & MASK;
    }

    /** Feature hashes that depend only on the request (computed once per auction). */
    public static int[] requestFeatures(String pub, String cat, String dev, String geo, int hour, java.util.List<String> segs) {
        int[] f = new int[5 + segs.size()];
        f[0] = fnv1a("pub=" + pub);
        f[1] = fnv1a("cat=" + cat);
        f[2] = fnv1a("dev=" + dev);
        f[3] = fnv1a("geo=" + geo);
        f[4] = fnv1a("hr=" + hour);
        for (int i = 0; i < segs.size(); i++) f[5 + i] = fnv1a("seg=" + segs.get(i));
        return f;
    }

    /** CTR prediction for one (request, campaign) pair. */
    public double predict(int[] reqFeatures, int campaignHash) {
        Weights m = weights;
        double z = m.bias + m.w[index(0, campaignHash)];
        for (int f : reqFeatures) z += m.w[index(f, campaignHash)];
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public long version() {
        return weights.version;
    }

    public String source() {
        return weights.source;
    }

    public void install(float[] w, double bias, String source) {
        if (w.length != 1 << BITS) throw new IllegalArgumentException("expected " + (1 << BITS) + " weights");
        weights = new Weights(w, bias, weights.version + 1, source);
    }

    /** Sparse JSON: {"bias":-3.2,"weights":{"1234":0.5,...}}. Returns true if a new file was loaded. */
    public synchronized boolean reloadIfChanged(Path file) throws IOException {
        if (!Files.exists(file)) return false;
        long mod = Files.getLastModifiedTime(file).toMillis();
        if (mod == lastModified) return false;
        JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(file));
        float[] w = new float[1 << BITS];
        Iterator<Map.Entry<String, JsonNode>> it = root.get("weights").fields();
        while (it.hasNext()) {
            var e = it.next();
            w[Integer.parseInt(e.getKey()) & MASK] = (float) e.getValue().asDouble();
        }
        install(w, root.path("bias").asDouble(DEFAULT_BIAS), file.getFileName().toString());
        lastModified = mod;
        return true;
    }
}
