package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.firewalldo.portal.SessionService.Actor;

@RestController
@RequestMapping("/api")
class AccountsController {
    private final Store db;
    private final SessionService sessions;

    AccountsController(Store db, SessionService sessions) {
        this.db = db;
        this.sessions = sessions;
    }

    @PostMapping("/auth/login")
    ResponseEntity<Map<String, JsonNode>> login(@RequestBody JsonNode body) {
        var input = new Input(body);
        String email = SessionService.email(input.text("email", 3, 254));
        String password = input.password("senha", 1);
        sessions.throttle(email);
        var login = sessions.login(email, password);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, login.cookie().toString()).body(Map.of("user", login.user()));
    }

    @PostMapping("/auth/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void activate(@RequestBody JsonNode body) {
        var input = new Input(body);
        sessions.activate(input.text("token", 43, 43), input.password("senha", 12));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessions.logout(request).toString()).build();
    }

    @GetMapping("/auth/me")
    JsonNode me(@RequestAttribute Actor actor) { return sessions.me(actor); }

    @GetMapping("/users")
    List<JsonNode> users() {
        return db.list("SELECT " + SessionService.MEMBER + " FROM members m ORDER BY m.name, m.id");
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode create(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        var input = new Input(body);
        UUID id = UUID.randomUUID();
        db.sql.update("INSERT INTO members (id, name, email, role, title) VALUES (?, ?, ?, ?, ?)",
            id, input.text("name", 2, 100), SessionService.email(input.text("email", 3, 254)),
            input.choice("role", "admin", "usuario"), input.text("title", 0, 100));
        return ((ObjectNode) sessions.member(id)).put("inviteToken", sessions.invite(id));
    }

    @PostMapping("/users/{id}/invite")
    @Transactional
    Map<String, String> invite(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireAdmin();
        UUID memberId = Input.id(id);
        var member = lockMember(memberId);
        if (!member.path("active").asBoolean() || !member.path("password_hash").isNull()) {
            throw ApiError.conflict("Convites são destinados a usuários ativos que ainda precisam criar a senha.");
        }
        return Map.of("inviteToken", sessions.invite(memberId));
    }

    @PatchMapping("/users/{id}")
    @Transactional
    JsonNode update(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        UUID memberId = Input.id(id);
        var input = new Input(body);
        if (!actor.admin()) {
            if (!actor.id().equals(memberId) || input.has("role") || input.has("active") || input.has("areas")) {
                throw ApiError.forbidden();
            }
            input.only("name", "title", "bio");
        } else {
            input.only("name", "title", "bio", "role", "active", "areas");
        }
        db.lock(SessionService.ADMIN_LOCK);
        var actual = db.first("SELECT to_jsonb(m) FROM members m WHERE id = ? AND active", actor.id());
        if (actual == null || (actor.admin() && !actual.path("role").asText().equals("admin"))) throw ApiError.forbidden();
        var old = lockMember(memberId);
        boolean active = input.has("active") ? input.bool("active") : old.path("active").asBoolean();
        String role = input.has("role") ? input.choice("role", "admin", "usuario") : old.path("role").asText();
        if (old.path("active").asBoolean() && old.path("role").asText().equals("admin")
            && (!active || !role.equals("admin"))
            && db.first("SELECT to_jsonb(id) FROM members WHERE id <> ? AND " + SessionService.ACTIVE_ADMIN + " LIMIT 1", memberId) == null) {
            throw ApiError.conflict("Mantenha pelo menos um administrador com acesso ativo.");
        }
        db.sql.update("""
            UPDATE members SET name = COALESCE(?, name), title = COALESCE(?, title), bio = COALESCE(?, bio),
              role = ?, active = ?, areas = COALESCE(?::text[], areas) WHERE id = ?
            """, input.has("name") ? input.text("name", 2, 100) : null,
            input.has("title") ? input.text("title", 0, 100) : null,
            input.has("bio") ? input.text("bio", 0, 2000) : null, role, active,
            input.has("areas") ? Store.array(Input.unique(Input.list(body.path("areas"), 6, item -> {
                if (!item.isTextual() || !List.of(Input.AREAS).contains(item.textValue())) throw ApiError.invalid("Área inválida.");
                return item.textValue();
            }))) : null, memberId);
        if (!active) db.sql.update("DELETE FROM sessions WHERE member_id = ?", memberId);
        return sessions.member(memberId);
    }

    private JsonNode lockMember(UUID id) {
        return db.require("Usuário não encontrado.", "SELECT to_jsonb(m) FROM members m WHERE id = ? FOR UPDATE", id);
    }
}
