package io.adexchange.app.bid;

import io.adexchange.app.config.ExchangeProperties;
import io.adexchange.auction.model.BidRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** OpenRTB-lite ingress. 204 = no-bid (reason in header); 400 = malformed; 429 = per-IP rate limit. */
@RestController
class BidController {
    private final BidGateway gateway;
    private final int ipLimitPerSec;
    private final Map<String, AtomicInteger> ipCounts = new ConcurrentHashMap<>();
    private volatile long second = System.currentTimeMillis() / 1000;

    BidController(BidGateway gateway, ExchangeProperties props) {
        this.gateway = gateway;
        this.ipLimitPerSec = props.ipRateLimitPerSec();
    }

    static String validate(BidRequest r) {
        if (r == null) return "empty body";
        if (r.id() == null || r.id().isBlank() || r.id().length() > 64) return "id";
        if (r.userId() == null || r.userId().isBlank() || r.userId().length() > 64) return "userId";
        if (r.publisherId() == null || r.publisherId().length() > 64) return "publisherId";
        if (r.geo() == null || r.device() == null || r.siteCategory() == null) return "geo/device/siteCategory";
        if (r.geo().length() > 8 || r.device().length() > 16 || r.siteCategory().length() > 32) return "field too long";
        if (r.segments() == null || r.segments().size() > 20) return "segments";
        for (String s : r.segments()) if (s == null || s.length() > 32) return "segment";
        if (r.slots() == null || r.slots().isEmpty() || r.slots().size() > 8) return "slots";
        for (BidRequest.Slot s : r.slots()) {
            if (s == null || s.id() == null || s.id().length() > 32 || s.w() <= 0 || s.h() <= 0 || s.w() > 4096 || s.h() > 4096
                    || s.floorCpmMicros() < 0) return "slot";
        }
        return null;
    }

    private boolean limited(HttpServletRequest req) {
        if (ipLimitPerSec <= 0) return false;
        long now = System.currentTimeMillis() / 1000;
        if (now != second) {
            synchronized (this) {
                if (now != second) {
                    ipCounts.clear();
                    second = now;
                }
            }
        }
        return ipCounts.computeIfAbsent(req.getRemoteAddr(), k -> new AtomicInteger()).incrementAndGet() > ipLimitPerSec;
    }

    @PostMapping("/openrtb/bid")
    ResponseEntity<?> bid(@RequestBody BidRequest req, HttpServletRequest http) {
        if (limited(http)) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        String bad = validate(req);
        if (bad != null) {
            gateway.invalid.increment();
            return ResponseEntity.badRequest().body(Map.of("error", "invalid request: " + bad));
        }
        BidGateway.Result r = gateway.handle(req);
        if (r.noBid()) return ResponseEntity.noContent().header("X-No-Bid-Reason", r.nbr()).build();
        return ResponseEntity.ok(r);
    }
}
