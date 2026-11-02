package io.adexchange.app.track;

import io.adexchange.app.config.ExchangeProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only JSONL feed of impressions and clicks: the training input for ml/ (in full mode this is the
 * Kafka topic). Bounded queue + size cap: if the disk or consumer is slow we drop and count, never block.
 */
@Component
public class EventLog {
    private static final Logger log = LoggerFactory.getLogger(EventLog.class);
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(100_000);
    public final AtomicLong dropped = new AtomicLong();
    public final AtomicLong written = new AtomicLong();
    private final Thread writer;
    private volatile boolean running = true;

    public EventLog(ExchangeProperties p) {
        Path path = (p.eventsLog() == null || p.eventsLog().isBlank()) ? null : Path.of(p.eventsLog());
        if (path == null) {
            writer = null;
            return;
        }
        writer = Thread.ofPlatform().daemon().name("event-log").start(() -> run(path));
    }

    private void run(Path path) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            long size = Files.exists(path) ? Files.size(path) : 0;
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                while (running || !queue.isEmpty()) {
                    String line = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        w.flush();
                        continue;
                    }
                    if (size > MAX_BYTES) {
                        dropped.incrementAndGet();
                        continue;
                    }
                    w.write(line);
                    w.newLine();
                    size += line.length() + 1;
                    written.incrementAndGet();
                }
            }
        } catch (IOException | InterruptedException e) {
            log.warn("event log writer stopped: {}", e.toString());
        }
    }

    public void impression(TrackingSigner.Ticket t) {
        if (writer == null) return;
        String segs = t.segs().isEmpty() ? "" : "\"" + t.segs().replace(",", "\",\"") + "\"";
        offer("{\"t\":\"imp\",\"id\":\"" + esc(t.eventId()) + "\",\"ts\":" + System.currentTimeMillis()
                + ",\"camp\":\"" + esc(t.campaign()) + "\",\"pub\":\"" + esc(t.pub()) + "\",\"cat\":\"" + esc(t.cat())
                + "\",\"dev\":\"" + esc(t.dev()) + "\",\"geo\":\"" + esc(t.geo()) + "\",\"hr\":" + t.hour()
                + ",\"segs\":[" + segs + "],\"pctr\":" + t.pctr() + "}");
    }

    public void click(TrackingSigner.Ticket t) {
        if (writer == null) return;
        offer("{\"t\":\"click\",\"id\":\"" + esc(t.eventId()) + "\",\"ts\":" + System.currentTimeMillis() + "}");
    }

    private void offer(String line) {
        if (!queue.offer(line)) dropped.incrementAndGet();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @PreDestroy
    void stop() {
        running = false;
    }
}
