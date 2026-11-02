package io.adexchange.loadgen;

import com.sun.management.OperatingSystemMXBean;
import org.HdrHistogram.Histogram;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load generator. Arrivals are scheduled on a fixed timeline (rate profile) independent of response
 * times, and latency is measured from the INTENDED send instant, so a stalled server cannot hide its own
 * queueing delay (no coordinated omission). Payloads are pre-generated from a seeded RNG for reproducible runs.
 *
 * <pre>
 * java -jar simulator.jar --url http://localhost:8091 --scenario steady --rate 5000 --duration 60 --warmup 10 --out bench/results/steady.json
 * </pre>
 */
public final class LoadGen {

    static final String[] GEOS = {"IN", "US", "GB", "DE", "BR", "ID", "JP", "FR"};
    static final int[] GEO_W = {30, 25, 8, 7, 10, 10, 5, 5};
    static final String[] DEVICES = {"mobile", "desktop", "tablet", "ctv"};
    static final String[] SEGS = {"sports", "news", "finance", "gaming", "travel", "fashion", "tech", "food", "auto", "health",
            "music", "movies", "parenting", "education", "fitness", "realestate", "shopping", "diy", "pets", "outdoors"};
    static final String[] CATS = {"news", "sports", "tech", "finance", "entertainment", "lifestyle", "travel", "gaming", "food", "auto", "health", "education"};
    static final int[][] SIZES = {{300, 250}, {728, 90}, {320, 50}, {160, 600}};

    static Map<String, String> parse(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < a.length; i += 2) m.put(a[i].replaceFirst("^--", ""), a[i + 1]);
        return m;
    }

    static String payload(SplittableRandom r, int n) {
        int u = (int) Math.min(999_999, Math.pow(1_000_000, r.nextDouble()));
        int h = u * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        int w = (h & 0xff) % 100, acc = 0, gi = 0;
        for (; gi < GEOS.length; gi++) {
            acc += GEO_W[gi];
            if (w < acc) break;
        }
        gi = Math.min(gi, GEOS.length - 1);
        int dv = ((h >>> 8) & 0xff) % 100;
        String device = (gi == 0 || gi == 5 || gi == 4) ? (dv < 80 ? "mobile" : dv < 95 ? "desktop" : "tablet") : (dv < 45 ? "mobile" : dv < 85 ? "desktop" : dv < 95 ? "ctv" : "tablet");
        int pub = (int) Math.min(299, Math.pow(300, r.nextDouble()));
        int nseg = 1 + ((h >>> 16) & 3) % 3;
        StringBuilder segs = new StringBuilder();
        for (int k = 0; k < nseg; k++) {
            if (k > 0) segs.append(',');
            segs.append('"').append(SEGS[Math.floorMod(gi * 3 + k * 7 + (h >>> 18) % 5, SEGS.length)]).append('"');
        }
        int nslots = 1 + (pub % 3 == 0 ? 1 : 0);
        long floor = 100_000L + (pub * 2654435761L % 7) * 400_000L;
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < nslots; i++) {
            int[] sz = SIZES[(pub + i) % 4];
            if (i > 0) slots.append(',');
            slots.append("{\"id\":\"s").append(i).append("\",\"w\":").append(sz[0]).append(",\"h\":").append(sz[1])
                    .append(",\"floorCpmMicros\":").append(floor).append('}');
        }
        return "{\"id\":\"lg-" + n + "\",\"publisherId\":\"pub" + pub + "\",\"siteCategory\":\"" + CATS[pub % CATS.length]
                + "\",\"device\":\"" + device + "\",\"geo\":\"" + GEOS[gi] + "\",\"userId\":\"u" + u + "\",\"segments\":[" + segs
                + "],\"slots\":[" + slots + "]}";
    }

    static double rateAt(String scenario, double base, double t, double total) {
        return switch (scenario) {
            case "ramp" -> base * Math.min(1.0, 0.1 + 0.9 * t / Math.max(1, total * 0.8));
            case "spike" -> base * ((t % 30) >= 20 ? 3.0 : 1.0);
            default -> base; // steady, soak
        };
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> o = parse(args);
        String url = o.getOrDefault("url", "http://localhost:8091") + "/openrtb/bid";
        String scenario = o.getOrDefault("scenario", "steady");
        double rate = Double.parseDouble(o.getOrDefault("rate", "2000"));
        int duration = Integer.parseInt(o.getOrDefault("duration", "30"));
        int warmup = Integer.parseInt(o.getOrDefault("warmup", "5"));
        long seed = Long.parseLong(o.getOrDefault("seed", "42"));
        String out = o.getOrDefault("out", "bench/results/run.json");
        String label = o.getOrDefault("label", scenario);
        int maxOutstanding = Integer.parseInt(o.getOrDefault("max-outstanding", "20000"));

        int pool = 200_000;
        SplittableRandom rng = new SplittableRandom(seed);
        String[] payloads = new String[pool];
        for (int i = 0; i < pool; i++) payloads[i] = payload(rng, i);

        Histogram hist = new Histogram(1, 60_000_000_000L, 3);
        AtomicLong ok = new AtomicLong(), noBid = new AtomicLong(), shed = new AtomicLong(), other = new AtomicLong(),
                errors = new AtomicLong(), sent = new AtomicLong(), outstanding = new AtomicLong(), skipped = new AtomicLong();
        URI base = URI.create(url);
        String host = base.getHost();
        int port = base.getPort() < 0 ? 80 : base.getPort();
        int connections = Integer.parseInt(o.getOrDefault("connections", "128"));
        LinkedBlockingQueue<Work> queue = new LinkedBlockingQueue<>();
        boolean[] stop = {false};
        for (int c = 0; c < connections; c++) {
            Thread.ofPlatform().daemon().start(() -> worker(host, port, base.getRawPath(), queue, stop, hist, ok, noBid, shed, other, errors, outstanding));
        }
        long warmupNs = warmup * 1_000_000_000L;
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        System.out.printf("loadgen: %s rate=%.0f/s duration=%ds warmup=%ds seed=%d -> %s%n", scenario, rate, duration, warmup, seed, url);
        long total = duration + warmup;
        long start = System.nanoTime();
        long endNs = start + total * 1_000_000_000L;
        double cumulative = 0; // expected arrivals so far (integral of the rate profile)
        long lastNs = start, issued = 0;
        long cpuStartNs = os.getProcessCpuTime();
        long cpuWindowStart = System.nanoTime();
        double peakCpu = 0;
        long lastCpuTime = cpuStartNs, lastCpuWall = cpuWindowStart;

        while (true) {
            long now = System.nanoTime();
            if (now >= endNs) break;
            long prev = lastNs;
            cumulative += rateAt(scenario, rate, (now - start) / 1e9, total) * (now - lastNs) / 1e9;
            lastNs = now;
            // Every arrival that became due since the previous check is stamped with its intended instant, spread
            // evenly over (prev, now]. Latency is measured from that stamp, so loop/scheduling delay counts against us.
            long due = (long) cumulative - issued;
            long j = 0;
            while (issued < (long) cumulative) {
                long intended = prev + (now - prev) * (++j) / Math.max(1, due);
                final long intendedNs = intended;
                final boolean measured = intendedNs - start >= warmupNs;
                int idx = (int) (issued % pool);
                issued++;
                if (outstanding.get() >= maxOutstanding) {
                    skipped.incrementAndGet();
                    errors.incrementAndGet();
                    continue;
                }
                outstanding.incrementAndGet();
                sent.incrementAndGet();
                queue.add(new Work(intendedNs, payloads[idx], measured));
            }
            long wall = System.nanoTime();
            if (wall - lastCpuWall > 1_000_000_000L) {
                long cpu = os.getProcessCpuTime();
                peakCpu = Math.max(peakCpu, (cpu - lastCpuTime) / (double) (wall - lastCpuWall) * 100.0);
                lastCpuTime = cpu;
                lastCpuWall = wall;
            }
            Thread.onSpinWait(); // spin: accurate arrival schedule (costs one core, reported as loadgen CPU)
        }
        for (int i = 0; i < 100 && outstanding.get() > 0; i++) Thread.sleep(100); // drain
        stop[0] = true;
        double wallSec = (System.nanoTime() - start) / 1e9;
        double avgCpu = (os.getProcessCpuTime() - cpuStartNs) / 1e9 / wallSec * 100.0;
        int cores = Runtime.getRuntime().availableProcessors();

        long measuredCount = ok.get() + noBid.get() + shed.get() + other.get();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("label", label);
        res.put("scenario", scenario);
        res.put("timestamp", Instant.now().toString());
        res.put("targetRate", rate);
        res.put("durationSec", duration);
        res.put("warmupSec", warmup);
        res.put("seed", seed);
        res.put("achievedRate", measuredCount / (double) duration);
        res.put("requestsMeasured", measuredCount);
        res.put("bids200", ok.get());
        res.put("noBid204", noBid.get());
        res.put("shed", shed.get());
        res.put("other", other.get());
        res.put("errors", errors.get());
        res.put("skippedOutstandingCap", skipped.get());
        res.put("errorPct", measuredCount + errors.get() == 0 ? 0 : 100.0 * (errors.get() + other.get()) / (measuredCount + errors.get()));
        res.put("noBidPct", measuredCount == 0 ? 0 : 100.0 * noBid.get() / measuredCount);
        res.put("latencyMsFromIntendedSend", Map.of(
                "p50", hist.getValueAtPercentile(50) / 1e6, "p95", hist.getValueAtPercentile(95) / 1e6,
                "p99", hist.getValueAtPercentile(99) / 1e6, "p999", hist.getValueAtPercentile(99.9) / 1e6,
                "max", hist.getMaxValue() / 1e6));
        res.put("clientQueueWaitMs", Map.of("p50", QUEUE_WAIT.getValueAtPercentile(50) / 1e6, "p99", QUEUE_WAIT.getValueAtPercentile(99) / 1e6));
        res.put("serviceTimeMs", Map.of("p50", SERVICE.getValueAtPercentile(50) / 1e6, "p99", SERVICE.getValueAtPercentile(99) / 1e6));
        res.put("loadGenCpuPctOfOneCoreAvg", Math.round(avgCpu * 10) / 10.0);
        res.put("loadGenCpuPctOfOneCorePeak", Math.round(peakCpu * 10) / 10.0);
        res.put("hardware", Map.of("os", System.getProperty("os.name"), "logicalCores", cores,
                "java", System.getProperty("java.version"),
                "totalMemoryGB", Math.round(os.getTotalMemorySize() / 1e9 * 10) / 10.0));

        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (var e : res.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": ").append(toJson(e.getValue()));
            sb.append(++i < res.size() ? ",\n" : "\n");
        }
        sb.append("}\n");
        Path p = Path.of(out);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p))) {
            w.print(sb);
        }
        System.out.print(sb);
        System.exit(0);
    }


    static final Histogram QUEUE_WAIT = new Histogram(1, 60_000_000_000L, 3);
    static final Histogram SERVICE = new Histogram(1, 60_000_000_000L, 3);

    record Work(long intendedNs, String body, boolean measured) {}

    /** One keep-alive connection: send, read full response, record latency from the intended send time. */
    static void worker(String host, int port, String path, LinkedBlockingQueue<Work> queue, boolean[] stop, Histogram hist,
                       AtomicLong ok, AtomicLong noBid, AtomicLong shed, AtomicLong other, AtomicLong errors, AtomicLong outstanding) {
        Socket sock = null;
        InputStream in = null;
        OutputStream out = null;
        while (!stop[0]) {
            Work w;
            try {
                w = queue.poll(100, TimeUnit.MILLISECONDS); // wakes on add(); parkNanos on Windows sleeps in ~1-15 ms quanta
            } catch (InterruptedException e) {
                return;
            }
            if (w == null) continue;
            long pickedNs = System.nanoTime();
            try {
                if (sock == null || sock.isClosed()) {
                    sock = new Socket(host, port);
                    sock.setTcpNoDelay(true);
                    sock.setSoTimeout(10_000);
                    in = new BufferedInputStream(sock.getInputStream(), 8192);
                    out = new BufferedOutputStream(sock.getOutputStream(), 4096);
                }
                byte[] body = w.body().getBytes(StandardCharsets.UTF_8);
                out.write(("POST " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                int status = readResponse(in);
                if (lastConnClose) {
                    sock.close();
                    sock = null;
                }
                long endNs = System.nanoTime();
                long lat = endNs - w.intendedNs();
                if (w.measured()) {
                    synchronized (QUEUE_WAIT) {
                        QUEUE_WAIT.recordValue(Math.max(1, pickedNs - w.intendedNs()));
                        SERVICE.recordValue(Math.max(1, endNs - pickedNs));
                    }
                    synchronized (hist) {
                        hist.recordValue(Math.max(1, Math.min(lat, 59_000_000_000L)));
                    }
                    if (status == 200) ok.incrementAndGet();
                    else if (status == 204) {
                        if (lastNoBidShed) shed.incrementAndGet(); else noBid.incrementAndGet();
                    } else other.incrementAndGet();
                }
            } catch (IOException e) {
                if (w.measured()) errors.incrementAndGet();
                try { if (sock != null) sock.close(); } catch (IOException ignored) { }
                sock = null;
            } finally {
                outstanding.decrementAndGet();
            }
        }
    }

    static volatile boolean lastConnClose;
    static volatile boolean lastNoBidShed; // best-effort flag; shed is also visible server-side (gateway.shed)

    /** Minimal HTTP/1.1 response reader (status, Content-Length or chunked body). */
    static int readResponse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) throw new EOFException();
        int status = Integer.parseInt(line.substring(9, 12));
        long len = 0;
        boolean chunked = false, shed = false, close = false;
        while (!(line = readLine(in)).isEmpty()) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("content-length:")) len = Long.parseLong(l.substring(15).trim());
            else if (l.startsWith("transfer-encoding:") && l.contains("chunked")) chunked = true;
            else if (l.startsWith("connection:") && l.contains("close")) close = true;
            else if (l.startsWith("x-no-bid-reason:") && l.contains("shed")) shed = true;
        }
        lastNoBidShed = shed;
        lastConnClose = close;
        if (status == 204 || status == 304) return status;
        if (chunked) {
            while (true) {
                int size = Integer.parseInt(readLine(in).trim(), 16);
                if (size == 0) { readLine(in); break; }
                in.skipNBytes(size);
                readLine(in);
            }
        } else if (len > 0) {
            in.skipNBytes(len);
        }
        return status;
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    static String toJson(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder s = new StringBuilder("{");
            int i = 0;
            for (var e : new TreeMap<>(m).entrySet()) {
                if (i++ > 0) s.append(", ");
                s.append('"').append(e.getKey()).append("\": ").append(toJson(e.getValue()));
            }
            return s.append('}').toString();
        }
        if (v instanceof String s) return '"' + s.replace("\"", "\\\"") + '"';
        if (v instanceof Double d) return String.format(Locale.ROOT, "%.4f", d);
        return String.valueOf(v);
    }
}
