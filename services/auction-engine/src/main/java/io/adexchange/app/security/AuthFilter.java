package io.adexchange.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.adexchange.auth.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Every /api/** call needs a valid bearer token except login. Role checks happen in the controllers. */
@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String ATTR = "claims";
    private final Jwt jwt;

    public AuthFilter(Jwt jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        String p = r.getRequestURI();
        return !p.startsWith("/api/") || p.equals("/api/auth/login") || p.equals("/api/public/summary");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        io.adexchange.auth.Jwt.Claims c = (h != null && h.startsWith("Bearer ")) ? jwt.verify(h.substring(7)) : null;
        if (c == null) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        req.setAttribute(ATTR, c);
        chain.doFilter(req, res);
    }

    public static io.adexchange.auth.Jwt.Claims claims(HttpServletRequest r) {
        return (io.adexchange.auth.Jwt.Claims) r.getAttribute(ATTR);
    }
}
