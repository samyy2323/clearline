package io.adexchange.app.auctions;

import io.adexchange.app.security.AuthFilter;
import io.adexchange.auction.AuctionTrace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Raw auction ladders show every competitor's bid, so they are an operator view: admin only (advertisers must never see rivals' bids). */
@RestController
@RequestMapping("/api/auctions")
class AuctionFeedController {

    private final AuctionFeed feed;

    AuctionFeedController(AuctionFeed feed) {
        this.feed = feed;
    }

    private static void admin(HttpServletRequest r) {
        if (!AuthFilter.claims(r).admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
    }

    @GetMapping("/recent")
    List<AuctionTrace> recent(HttpServletRequest r) {
        admin(r);
        return feed.recent(30);
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    SseEmitter stream(HttpServletRequest r) {
        admin(r);
        return feed.subscribe();
    }
}
