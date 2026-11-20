package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SessionService implements ApplicationRunner {
    static final long ADMIN_LOCK = 470002;
    static final String ACTIVE_ADMIN = "role IN ('superadmin', 'admin') AND active AND password_hash IS NOT NULL";
    static final String MEMBER = """
        jsonb_build_object('id', m.id, 'name', m.name, 'email', m.email,
          'initials', upper(left(split_part(m.name, ' ', 1), 1) || left(split_part(m.name, ' ', 2), 1)),
          'role', m.role, 'title', m.title, 'bio', m.bio, 'areas', m.areas, 'active', m.active,
          'color', '#2563eb', 'pendingInvite', m.password_hash IS NULL,
          'status', CASE WHEN m.active AND m.last_seen > now() - interval '5 minutes' THEN 'online' ELSE 'offline' END,
          'financeAccess', m.finance_access,
          'avatarUrl', (SELECT '/api/users/' || ph.member_id || '/photo?v=' || floor(extract(epoch FROM ph.updated_at))::bigint
                        FROM member_photos ph WHERE ph.member_id = m.id))
        """;

    private static final Duration SESSION_AGE = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Pbkdf2PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummy = encoder.encode(token());
    private final Store db;
    private final boolean secure;
    private final String cookieName;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    SessionService(Store db, @Value("${SESSION_SECURE:true}") boolean secure,
                   @Value("${ADMIN_EMAIL:}") String adminEmail, @Value("${ADMIN_PASSWORD:}") String adminPassword,
                   @Value("${ADMIN_NAME:Administrador}") String adminName) {
        this.db = db;
        this.secure = secure;
        this.cookieName = secure ? "__Host-fw_session" : "fw_session";
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        db.lock(ADMIN_LOCK);
        if (db.first("SELECT to_jsonb(id) FROM members WHERE role = 'superadmin' AND active AND password_hash IS NOT NULL LIMIT 1") != null) return;
        var admins = db.list("SELECT to_jsonb(m) FROM members m WHERE " + ACTIVE_ADMIN + " ORDER BY id");
        if (!admins.isEmpty()) {
            var primary = admins.stream().filter(m -> m.path("email").asText().equalsIgnoreCase(adminEmail.strip())).findFirst()
                .orElse(admins.size() == 1 ? admins.get(0) : null);
            if (primary == null) throw new IllegalStateException("ADMIN_EMAIL deve identificar o administrador inicial para promover a administrador geral.");
            db.sql.update("UPDATE members SET role = 'superadmin' WHERE id = ?", Input.id(primary.path("id").asText()));
            return;
        }
        if (adminPassword.length() < 12 || adminPassword.length() > 128 || adminName.isBlank()
            || adminName.length() > 100 || adminEmail.isBlank()) {
            throw new IllegalStateException("Configure ADMIN_EMAIL e ADMIN_PASSWORD (12 a 128 caracteres).");
        }
        db.sql.update("INSERT INTO members (id, email, password_hash, name, role) VALUES (?, ?, ?, ?, 'superadmin')",
            UUID.randomUUID(), email(adminEmail), encoder.encode(adminPassword), adminName.strip());
    }

    static String email(String address) {
        String email = address.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw ApiError.invalid("Informe um e-mail válido.");
        }
        return email;
    }

    record Actor(UUID id, String role) {
        boolean general() { return role.equals("superadmin"); }
        boolean admin() { return general() || role.equals("admin"); }
        void requireAdmin() { if (!admin()) throw ApiError.forbidden(); }
        /** Financeiro: administradores ou usuários autorizados individualmente (members.finance_access). */
        void requireFinance(Store db) {
            if (admin()) return;
            Boolean allowed = db.sql.queryForObject("SELECT finance_access FROM members WHERE id = ? AND active", Boolean.class, id);
            if (!Boolean.TRUE.equals(allowed)) throw ApiError.forbidden();
        }
    }

    record Login(JsonNode user, ResponseCookie cookie) {}

    void throttle(String address) {
        int count = db.sql.queryForObject("""
            INSERT INTO login_attempts (key, started_at, count) VALUES (?, now(), 1)
            ON CONFLICT (key) DO UPDATE SET
              count = CASE WHEN login_attempts.started_at < now() - interval '15 minutes' THEN 1 ELSE login_attempts.count + 1 END,
              started_at = CASE WHEN login_attempts.started_at < now() - interval '15 minutes' THEN now() ELSE login_attempts.started_at END
            RETURNING count
            """, Integer.class, address);
        if (count > 12) throw new ApiError(429, "RATE_LIMIT", "Muitas tentativas. Aguarde alguns minutos.");
    }

    @Transactional
    public Login login(String email, String password) {
        JsonNode member = db.first("SELECT to_jsonb(m) FROM members m WHERE m.email = ?", email);
        String hash = member == null ? null : member.path("password_hash").textValue();
        if (!encoder.matches(password, hash == null ? dummy : hash) || hash == null || !member.path("active").asBoolean()) {
            throw new ApiError(401, "INVALID_LOGIN", "E-mail ou senha inválidos.");
        }
        UUID id = Input.id(member.path("id").asText());
        var current = db.first("SELECT to_jsonb(m) FROM members m WHERE id = ? FOR UPDATE", id);
        if (!current.path("active").asBoolean() || !hash.equals(current.path("password_hash").asText())) {
            throw new ApiError(401, "INVALID_LOGIN", "E-mail ou senha inválidos.");
        }
        String token = token();
        db.sql.update("DELETE FROM login_attempts WHERE key = ?", email);
        db.sql.update("DELETE FROM sessions WHERE member_id = ? AND expires_at <= now()", id);
        db.sql.update("INSERT INTO sessions VALUES (?, ?, now() + interval '7 days')", digest(token), id);
        db.sql.update("UPDATE members SET last_seen = now() WHERE id = ?", id);
        return new Login(member(id), cookie(token, SESSION_AGE));
    }

    String hashPassword(String password) { return encoder.encode(password); }

    void verifyPassword(JsonNode member, String password) {
        String hash = member.path("password_hash").textValue();
        if (hash == null || !encoder.matches(password, hash)) throw ApiError.invalid("A senha atual está incorreta.");
    }

    JsonNode member(UUID id) {
        return db.require("Usuário não encontrado.", "SELECT " + MEMBER + " FROM members m WHERE id = ?", id);
    }

    Actor actor(HttpServletRequest request) {
        String key = readSession(request);
        var user = key == null ? null : db.first("""
            SELECT jsonb_build_object('id', m.id, 'role', m.role)
            FROM sessions s JOIN members m ON m.id = s.member_id
            WHERE s.token_hash = ? AND s.expires_at > now() AND m.active
            """, key);
        if (user == null) throw ApiError.unauthenticated();
        return new Actor(Input.id(user.path("id").asText()), user.path("role").asText());
    }

    JsonNode me(Actor actor) {
        db.sql.update("UPDATE members SET last_seen = now() WHERE id = ? AND active", actor.id());
        return member(actor.id());
    }

    ResponseCookie logout(HttpServletRequest request) {
        String key = readSession(request);
        if (key != null) db.sql.update("DELETE FROM sessions WHERE token_hash = ?", key);
        return cookie("", Duration.ZERO);
    }

    String invite(UUID member) {
        String token = token();
        db.sql.update("""
            INSERT INTO invitations VALUES (?, ?, now() + interval '48 hours')
            ON CONFLICT (member_id) DO UPDATE SET token_hash = excluded.token_hash, expires_at = excluded.expires_at
            """, digest(token), member);
        return token;
    }

    @Transactional
    public void activate(String token, String password) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw invalidInvite();
        String key = digest(token);
        var invitation = db.first("SELECT to_jsonb(member_id) FROM invitations WHERE token_hash = ? AND expires_at > now()", key);
        if (invitation == null) throw invalidInvite();
        UUID id = Input.id(invitation.asText());
        var member = db.first("SELECT to_jsonb(m) FROM members m WHERE id = ? FOR UPDATE", id);
        if (!member.path("active").asBoolean() || !member.path("password_hash").isNull()
            || db.sql.update("DELETE FROM invitations WHERE token_hash = ? AND expires_at > now()", key) != 1) {
            throw invalidInvite();
        }
        db.sql.queryForObject("SELECT set_config('portal.actor_id', ?, true)", String.class, id.toString());
        db.sql.queryForObject("SELECT set_config('portal.actor_name', ?, true)", String.class, member.path("name").asText());
        db.sql.queryForObject("SELECT set_config('portal.actor_email', ?, true)", String.class, member.path("email").asText());
        db.sql.update("UPDATE members SET password_hash = ? WHERE id = ?", encoder.encode(password), id);
    }

    private static ApiError invalidInvite() {
        return ApiError.invalid("Convite inválido ou expirado. Peça um novo convite.");
    }

    String readSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies).filter(cookie -> cookieName.equals(cookie.getName()))
            .map(Cookie::getValue).filter(value -> value.matches("[A-Za-z0-9_-]{43}"))
            .findFirst().map(SessionService::digest).orElse(null);
    }

    private ResponseCookie cookie(String value, Duration age) {
        return ResponseCookie.from(cookieName, value).httpOnly(true).secure(secure)
            .sameSite("Strict").path("/").maxAge(age).build();
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
