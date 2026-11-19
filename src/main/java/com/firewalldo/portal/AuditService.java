package com.firewalldo.portal;

import com.firewalldo.portal.SessionService.Actor;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AuditService {
    private final Store db;
    private final TransactionTemplate independent;
    private final boolean railway;

    AuditService(Store db, PlatformTransactionManager manager, @Value("${RAILWAY_ENVIRONMENT_ID:}") String environment) {
        this.db = db;
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.railway = !environment.isBlank();
    }

    void context(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("actor");
        var member = actor == null ? null : db.first("SELECT jsonb_build_object('name',name,'email',email) FROM members WHERE id = ?", actor.id());
        config("actor_id", actor == null ? "" : actor.id().toString());
        config("actor_name", member == null ? "Sistema" : member.path("name").asText());
        config("actor_email", member == null ? "" : member.path("email").asText());
        config("ip", ip(request));
    }

    private void config(String name, String value) {
        db.sql.queryForObject("SELECT set_config(?, ?, true)", String.class, "portal." + name, value == null ? "" : value);
    }

    String ip(HttpServletRequest request) {
        // API is private behind the portal. Railway HTTP edge provides X-Real-IP;
        // Caddy overwrites X-Portal-Client-IP with that header (never a browser value).
        String address = railway ? request.getHeader("X-Portal-Client-IP") : request.getRemoteAddr();
        if (address == null || address.length() > 64 || !address.matches("[0-9a-fA-F:.]+")) return null;
        try { return InetAddress.getByName(address).getHostAddress(); }
        catch (Exception invalid) { return null; }
    }

    void access(HttpServletRequest request, int status) {
        String path = request.getRequestURI();
        boolean login = path.equals("/api/auth/login");
        boolean logout = path.equals("/api/auth/logout");
        boolean activation = path.equals("/api/auth/activate");
        if (!login && !logout && !activation && status < 400) return;
        independent.executeWithoutResult(tx -> {
            Actor actor = (Actor) request.getAttribute("actor");
            String attempted = (String) request.getAttribute("loginEmail");
            var member = actor != null ? db.first("SELECT to_jsonb(m) FROM members m WHERE id = ?", actor.id())
                : attempted != null ? db.first("SELECT to_jsonb(m) FROM members m WHERE email = ?", attempted) : null;
            boolean unverified = login && status >= 400;
            String target = attempted == null ? "" : member == null ? attempted : member.path("name").asText() + " (" + attempted + ")";
            String operation = login ? "login" : logout ? "logout" : activation ? "ativacao" : "acesso";
            // Record patterns rather than request bodies, cookies or invite tokens.
            Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern == null ? "/api" : pattern.toString();
            db.sql.update("""
                INSERT INTO audit_logs(actor_id,actor_name,actor_email,action,entity,target_name,ip,outcome,details)
                VALUES(?,?,?,?,?,?,?,?,jsonb_build_object('httpStatus',?,'route',?,'method',?))
                """, member == null || unverified ? null : Input.id(member.path("id").asText()),
                member == null || unverified ? "Sistema" : member.path("name").asText(),
                member == null || unverified ? "" : member.path("email").asText(),
                operation, "autenticacao", target, ip(request),
                status < 400 ? "sucesso" : status == 403 || status == 429 ? "bloqueado" : "falha", status, route, request.getMethod());
        });
    }
}
