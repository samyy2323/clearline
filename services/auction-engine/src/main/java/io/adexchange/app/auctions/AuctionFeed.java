package io.adexchange.app.auctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adexchange.auction.AuctionEngine;
import io.adexchange.auction.AuctionObserver;
import io.adexchange.auction.AuctionTrace;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects a handful of fully explained auctions per second for the "Live auction" page. {@link #wants()} is a rate limit (one atomic read),
 * and delivery to browsers happens on a separate single thread with a bounded queue, so a slow client can never slow an auction.
 */
@Component
public class AuctionFeed implements AuctionObserver {

    private static final long MIN_GAP_NANOS = 220_000_000L;    // about 4.5 traces per second at most
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicLong nextAllowed = new AtomicLong();
    private volatile long lastContestedNanos;
    private final ArrayDeque<AuctionTrace> recent = new ArrayDeque<>();
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200),
            r -> Thread.ofPlatform().daemon().name("auction-feed").unstarted(r), new ThreadPoolExecutor.DiscardPolicy());

    public AuctionFeed(AuctionEngine engine) {
        engine.setObserver(this);
    }

    @Override
    public boolean wants() {
        if (subscribers.isEmpty() && recent.size() >= 30) {
            // nobody is watching: keep the buffer warm but at a much lower rate
            long now = System.nanoTime(), nx = nextAllowed.get();
            return now >= nx && nextAllowed.compareAndSet(nx, now + 2_000_000_000L);
        }
        long now = System.nanoTime(), nx = nextAllowed.get();
        return now >= nx && nextAllowed.compareAndSet(nx, now + MIN_GAP_NANOS);
    }

    @Override
    public void accept(AuctionTrace t) {
        // The page is for explaining auctions, so keep the ones with a winner and real competition. If nothing contested has shown up for a while
        // (tight budgets, heavy pacing), let uncontested winners through so the page is never empty.
        boolean won = t.slots().stream().anyMatch(s -> s.winnerCampaign() != null);
        if (!won) return;
        boolean contested = t.slots().stream().anyMatch(s ->
                s.bidders().stream().filter(b -> "WON".equals(b.outcome()) || "OUTBID".equals(b.outcome())).count() >= 2);
        long now = System.nanoTime();
        if (contested) lastContestedNanos = now;
        else if (now - lastContestedNanos < 20_000_000_000L) return;
        synchronized (recent) {
            if (recent.size() >= 60) recent.pollFirst();
            recent.addLast(t);
        }
        if (subscribers.isEmpty()) return;
        sender.execute(() -> {
            String json;
            try {
                json = JSON.writeValueAsString(t);
            } catch (Exception e) {
                return;
            }
            for (SseEmitter e : subscribers) {
                try {
                    e.send(SseEmitter.event().name("auction").data(json));
                } catch (Exception ex) {
                    subscribers.remove(e);
                }
            }
        });
    }

    public List<AuctionTrace> recent(int n) {
        synchronized (recent) {
            List<AuctionTrace> all = new ArrayList<>(recent);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    public SseEmitter subscribe() {
        SseEmitter e = new SseEmitter(0L);
        subscribers.add(e);
        e.onCompletion(() -> subscribers.remove(e));
        e.onTimeout(() -> subscribers.remove(e));
        e.onError(x -> subscribers.remove(e));
        return e;
    }
}
