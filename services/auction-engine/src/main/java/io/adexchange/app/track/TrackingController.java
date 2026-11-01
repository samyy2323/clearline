package io.adexchange.app.track;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/** Public endpoints hit by browsers/ad servers; authenticated by the HMAC token, not by JWT. */
@RestController
class TrackingController {
    private static final byte[] GIF = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
    private final TrackerService tracker;

    TrackingController(TrackerService tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/track/{type:win|imp|click}")
    ResponseEntity<byte[]> track(@PathVariable String type, @RequestParam(required = false) String d,
                                 @RequestParam(required = false) String s, HttpServletRequest req) {
        TrackerService.Outcome o = tracker.handle(type, d, s, req.getRemoteAddr(), req.getHeader("User-Agent"));
        var b = switch (o) {
            case REJECTED_SIGNATURE, REJECTED_MALFORMED -> ResponseEntity.status(400);
            case REJECTED_EXPIRED -> ResponseEntity.status(410);
            default -> ResponseEntity.ok();
        };
        b.header("X-Track-Outcome", o.name()).cacheControl(CacheControl.noStore());
        if (type.equals("imp") && o == TrackerService.Outcome.ACCEPTED) return b.contentType(MediaType.IMAGE_GIF).body(GIF);
        return b.build();
    }
}
