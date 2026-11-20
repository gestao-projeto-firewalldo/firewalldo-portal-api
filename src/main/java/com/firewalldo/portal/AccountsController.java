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
    private final AuditService audit;

    AccountsController(Store db, SessionService sessions, AuditService audit) {
        this.db = db;
        this.sessions = sessions;
        this.audit = audit;
    }

    @PostMapping("/auth/login")
    ResponseEntity<Map<String, JsonNode>> login(@RequestBody JsonNode body, HttpServletRequest request) {
        var input = new Input(body);
        String email = SessionService.email(input.text("email", 3, 254));
        request.setAttribute("loginEmail", email);
        String password = input.password("senha", 1);
        sessions.throttle(email);
        var login = sessions.login(email, password);
        request.setAttribute("actor", new Actor(Input.id(login.user().path("id").asText()), login.user().path("role").asText()));
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
        try { request.setAttribute("actor", sessions.actor(request)); } catch (ApiError ignored) { /* already signed out */ }
        audit.context(request);
        if(request.getAttribute("actor") instanceof Actor actor) {
            String device=request.getHeader("X-Push-Device");
            if(device!=null && device.matches("[a-fA-F0-9-]{36}")) {
                db.sql.update("UPDATE push_deliveries SET status='expired',message='Notificações desativadas ao sair desta conta.' WHERE subscription_id=? AND member_id=? AND status IN ('queued','sending')",Input.id(device),actor.id());
                db.sql.update("DELETE FROM push_subscriptions WHERE id=? AND member_id=?",Input.id(device),actor.id());
            }
        }
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
        db.lock(SessionService.ADMIN_LOCK);
        actor = currentActor(actor);
        actor.requireAdmin();
        var input = new Input(body);
        input.only("name", "email", "role", "title", "bio", "areas", "senha");
        String role = input.choice("role", "superadmin", "admin", "usuario");
        if (!actor.general() && !role.equals("usuario")) throw ApiError.forbidden();
        UUID id = UUID.randomUUID();
        db.sql.update("INSERT INTO members (id, name, email, role, title, password_hash, bio, areas) VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[])",
            id, input.text("name", 2, 100), SessionService.email(input.text("email", 3, 254)), role,
            input.text("title", 0, 100), sessions.hashPassword(input.password("senha", 12)),
            input.has("bio") ? input.text("bio", 0, 2000) : "", input.has("areas") ? areas(body) : "{}");
        return sessions.member(id);
    }

    @PostMapping("/users/{id}/invite")
    @Transactional
    Map<String, String> invite(@RequestAttribute Actor actor, @PathVariable String id) {
        db.lock(SessionService.ADMIN_LOCK);
        actor = currentActor(actor);
        UUID memberId = Input.id(id);
        var member = lockMember(memberId);
        requireManage(actor, memberId, member);
        if (!member.path("active").asBoolean() || !member.path("password_hash").isNull()) {
            throw ApiError.conflict("Convites são destinados a usuários ativos que ainda precisam criar a senha.");
        }
        return Map.of("inviteToken", sessions.invite(memberId));
    }

    @PatchMapping("/users/{id}")
    @Transactional
    JsonNode update(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        UUID memberId = Input.id(id);
        var input = new Input(body);
        input.only("name", "email", "title", "bio", "role", "active", "areas", "senha", "confirmacaoSenha", "senhaAtual", "financeAccess");
        db.lock(SessionService.ADMIN_LOCK);
        actor = currentActor(actor);
        var old = lockMember(memberId);
        requireManage(actor, memberId, old);
        boolean self = actor.id().equals(memberId);
        String role = input.has("role") ? input.choice("role", "superadmin", "admin", "usuario") : old.path("role").asText();
        boolean active = input.has("active") ? input.bool("active") : old.path("active").asBoolean();
        if (!actor.general() && (!role.equals(old.path("role").asText()) || (self && active != old.path("active").asBoolean()))) throw ApiError.forbidden();
        String email = input.has("email") ? SessionService.email(input.text("email", 3, 254)) : old.path("email").asText();
        String hash = null;
        if (input.has("senha")) {
            String password = input.password("senha", 12);
            if (!password.equals(input.password("confirmacaoSenha", 12))) throw ApiError.invalid("As senhas precisam ser iguais.");
            hash = sessions.hashPassword(password);
        }
        if (role.equals("superadmin") && old.path("password_hash").isNull() && hash == null) {
            throw ApiError.conflict("Defina e confirme uma senha para este administrador geral poder acessar o portal.");
        }
        boolean sensitive = hash != null || !email.equals(old.path("email").asText());
        if (self && sensitive) sessions.verifyPassword(old, input.password("senhaAtual", 1));
        if (old.path("role").asText().equals("superadmin") && old.path("active").asBoolean()
            && (!active || !role.equals("superadmin"))
            && db.first("SELECT to_jsonb(id) FROM members WHERE id <> ? AND role = 'superadmin' AND active AND password_hash IS NOT NULL LIMIT 1", memberId) == null) {
            throw ApiError.conflict("Mantenha pelo menos um administrador geral com acesso ativo.");
        }
        db.sql.update("""
            UPDATE members SET name = COALESCE(?, name), email = ?, title = COALESCE(?, title), bio = COALESCE(?, bio),
              role = ?, active = ?, areas = COALESCE(?::text[], areas), password_hash = COALESCE(?, password_hash) WHERE id = ?
            """, input.has("name") ? input.text("name", 2, 100) : null, email,
            input.has("title") ? input.text("title", 0, 100) : null,
            input.has("bio") ? input.text("bio", 0, 2000) : null, role, active,
            input.has("areas") ? areas(body) : null, hash, memberId);
        if (input.has("financeAccess")) {
            boolean finance = input.bool("financeAccess");
            // Somente administradores concedem acesso ao Financeiro, e nunca a si mesmos.
            if (finance != old.path("finance_access").asBoolean() && (!actor.admin() || self)) throw ApiError.forbidden();
            db.sql.update("UPDATE members SET finance_access = ? WHERE id = ?", finance, memberId);
        }
        if (hash != null) db.sql.update("DELETE FROM invitations WHERE member_id = ?", memberId);
        if (!active || sensitive || !role.equals(old.path("role").asText())) {
            // Keep the caller's current session on a self-edit; invalidate all other sessions.
            String current = self && active ? sessions.readSession(request) : null;
            db.sql.update("DELETE FROM sessions WHERE member_id = ? AND (?::text IS NULL OR token_hash <> ?)", memberId, current, current);
        }
        return sessions.member(memberId);
    }

    /** Foto de perfil (V1.9). Guardada no banco; qualquer usuário autenticado pode vê-la. */
    @GetMapping("/users/{id}/photo")
    ResponseEntity<byte[]> photo(@PathVariable String id) {
        var rows = db.sql.query("SELECT content, media_type FROM member_photos WHERE member_id = ?",
            (row, index) -> ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, row.getString(2))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'")
                .body(row.getBytes(1)), Input.id(id));
        if (rows.isEmpty()) throw ApiError.missing("Foto não encontrada.");
        return rows.get(0);
    }

    @PutMapping("/users/{id}/photo")
    @Transactional
    JsonNode savePhoto(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        UUID memberId = Input.id(id);
        db.lock(SessionService.ADMIN_LOCK);
        actor = currentActor(actor);
        requireManage(actor, memberId, lockMember(memberId));
        var input = new Input(body);
        input.only("image");
        var image = input.upload("image", 1024 * 1024, "image/png", "image/jpeg", "image/webp");
        db.sql.update("""
            INSERT INTO member_photos (member_id, content, media_type, updated_at) VALUES (?, ?, ?, clock_timestamp())
            ON CONFLICT (member_id) DO UPDATE SET content = excluded.content, media_type = excluded.media_type, updated_at = clock_timestamp()
            """, memberId, image.bytes(), image.type());
        return sessions.member(memberId);
    }

    @DeleteMapping("/users/{id}/photo")
    @Transactional
    JsonNode deletePhoto(@RequestAttribute Actor actor, @PathVariable String id) {
        UUID memberId = Input.id(id);
        db.lock(SessionService.ADMIN_LOCK);
        actor = currentActor(actor);
        requireManage(actor, memberId, lockMember(memberId));
        db.sql.update("DELETE FROM member_photos WHERE member_id = ?", memberId);
        return sessions.member(memberId);
    }

    private Actor currentActor(Actor actor) {
        var current = db.require("Conta indisponível.", "SELECT to_jsonb(m) FROM members m WHERE id = ? AND active FOR UPDATE", actor.id());
        return new Actor(actor.id(), current.path("role").asText());
    }

    private void requireManage(Actor actor, UUID id, JsonNode member) {
        if (actor.id().equals(id) || actor.general() || (actor.admin() && member.path("role").asText().equals("usuario"))) return;
        throw ApiError.forbidden();
    }

    private String areas(JsonNode body) {
        return Store.array(Input.unique(Input.list(body.path("areas"), 6, item -> {
            if (!item.isTextual() || !List.of(Input.AREAS).contains(item.textValue())) throw ApiError.invalid("Área inválida.");
            return item.textValue();
        })));
    }

    private JsonNode lockMember(UUID id) {
        return db.require("Usuário não encontrado.", "SELECT to_jsonb(m) FROM members m WHERE id = ? FOR UPDATE", id);
    }
}
