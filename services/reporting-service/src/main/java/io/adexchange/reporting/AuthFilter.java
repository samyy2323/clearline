package io.adexchange.reporting;

import io.adexchange.auth.Jwt;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
class AuthFilter extends OncePerRequestFilter {
    static final String ATTR = "claims";
    private final Jwt jwt;

    AuthFilter(Jwt jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        Jwt.Claims c = (h != null && h.startsWith("Bearer ")) ? jwt.verify(h.substring(7)) : null;
        if (c == null) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        req.setAttribute(ATTR, c);
        chain.doFilter(req, res);
    }
}
