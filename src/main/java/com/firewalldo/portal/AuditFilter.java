package com.firewalldo.portal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class AuditFilter extends OncePerRequestFilter {
    private final AuditService audit;
    AuditFilter(AuditService audit) { this.audit = audit; }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || request.getRequestURI().equals("/api/health");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        boolean failed = false;
        try { chain.doFilter(req, res); }
        catch (ServletException | IOException | RuntimeException error) { failed = true; throw error; }
        finally { audit.access(req, failed ? 500 : res.getStatus()); }
    }
}
