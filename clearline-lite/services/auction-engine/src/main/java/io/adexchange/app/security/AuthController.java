package io.adexchange.app.security;

import io.adexchange.app.config.ExchangeProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
class JwtConfig {
    @Bean
    Jwt jwt(ExchangeProperties p) {
        return new Jwt(p.jwtSecret());
    }
}

/**
 * Demo identities: {@code admin} and {@code advertiser1..advertiser8} (each owns advertiser id {@code adv-N}).
 * Passwords come from ADMIN_PASSWORD / ADVERTISER_PASSWORD. Failed logins are rate limited per IP.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {
    record Login(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {}

    private final ExchangeProperties props;
    private final Jwt jwt;
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private volatile long windowStart = System.currentTimeMillis();

    AuthController(ExchangeProperties props, Jwt jwt) {
        this.props = props;
        this.jwt = jwt;
    }

    private static boolean eq(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody Login in, HttpServletRequest req) {
        long now = System.currentTimeMillis();
        if (now - windowStart > 60_000) {
            failures.clear();
            windowStart = now;
        }
        String ip = req.getRemoteAddr();
        if (failures.computeIfAbsent(ip, k -> new AtomicInteger()).get() >= 10)
            return ResponseEntity.status(429).body(Map.of("error", "too many attempts, retry in a minute"));

        String token = null;
        String role = null, adv = null;
        if (in.username().equals("admin") && eq(in.password(), props.adminPassword())) {
            role = "admin";
        } else if (in.username().matches("advertiser[1-8]") && eq(in.password(), props.advertiserPassword())) {
            role = "advertiser";
            adv = "adv-" + in.username().substring("advertiser".length());
        }
        if (role == null) {
            failures.get(ip).incrementAndGet();
            return ResponseEntity.status(401).body(Map.of("error", "invalid credentials"));
        }
        token = jwt.issue(in.username(), role, adv, 8 * 3600);
        String company = adv == null ? "Clearline Operations"
                : io.adexchange.app.sim.DemoData.ADVERTISERS[Integer.parseInt(adv.substring(4)) - 1];
        return ResponseEntity.ok(Map.of("token", token, "role", role, "advertiserId", adv == null ? "" : adv,
                "user", in.username(), "company", company));
    }
}
