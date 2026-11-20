package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.firewalldo.portal.SessionService.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class ContentController {
    private static final String IDEA = """
        SELECT jsonb_build_object('id', i.id, 'title', i.title, 'description', i.description,
          'category', i.category, 'status', i.status, 'authorId', i.author_id, 'createdAt', i.created_at,
          'votes', v.total, 'votedByMe', COALESCE(v.mine, false))
        FROM ideas i CROSS JOIN LATERAL (
          SELECT count(*) AS total, bool_or(member_id = ?) AS mine FROM idea_votes WHERE idea_id = i.id
        ) v
        """;
    private final Store db;

    ContentController(Store db) { this.db = db; }

    @GetMapping("/ideas")
    List<JsonNode> ideas(@RequestAttribute Actor actor) {
        return db.list(IDEA + " ORDER BY i.created_at DESC, i.id", actor.id());
    }

    @PostMapping("/ideas")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode createIdea(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        var form = new Input(body);
        UUID id = UUID.randomUUID();
        db.sql.update("INSERT INTO ideas (id, author_id, title, description, category, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            id, actor.id(), form.text("title", 3, 200), form.text("description", 0, 12000),
            form.choice("category", "video", "educacao", "redes_sociais", "pratica", "guia", "bastidores"), Input.today());
        return db.first(IDEA + " WHERE i.id = ?", actor.id(), id);
    }

    @PatchMapping("/ideas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateIdea(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        actor.requireAdmin();
        String status = new Input(body).choice("status", "nova", "em_analise", "aprovada", "arquivada");
        if (db.sql.update("UPDATE ideas SET status = ? WHERE id = ?", status, Input.id(id)) == 0) {
            throw ApiError.missing("Ideia não encontrada.");
        }
    }

    /** Exclusão definitiva (V1.9). Os votos são removidos em cascata pelo banco. */
    @DeleteMapping("/ideas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteIdea(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireAdmin();
        if (db.sql.update("DELETE FROM ideas WHERE id = ?", Input.id(id)) == 0) throw ApiError.missing("Ideia não encontrada.");
    }

    @PostMapping("/ideas/{id}/vote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void vote(@RequestAttribute Actor actor, @PathVariable String id) {
        UUID idea = Input.id(id);
        db.require("Ideia não encontrada.", "SELECT to_jsonb(id) FROM ideas WHERE id = ? FOR UPDATE", idea);
        if (db.sql.update("DELETE FROM idea_votes WHERE idea_id = ? AND member_id = ?", idea, actor.id()) == 0) {
            db.sql.update("INSERT INTO idea_votes VALUES (?, ?)", idea, actor.id());
        }
    }

    @GetMapping("/notifications")
    List<JsonNode> notifications(@RequestAttribute Actor actor) {
        return db.list("""
            SELECT jsonb_build_object('id', id, 'kind', kind, 'title', title, 'description', description,
              'createdAt', to_char(created_at AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI'),
              'createdAtIso', created_at, 'read', read)
            FROM notifications WHERE member_id = ? ORDER BY created_at DESC, id LIMIT 100
            """, actor.id());
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@RequestAttribute Actor actor, @PathVariable String id) {
        if (db.sql.update("UPDATE notifications SET read = true WHERE id = ? AND member_id = ?", Input.id(id), actor.id()) == 0) {
            throw ApiError.missing("Notificação não encontrada.");
        }
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void readAll(@RequestAttribute Actor actor) {
        db.sql.update("UPDATE notifications SET read = true WHERE member_id = ? AND NOT read", actor.id());
    }


    @GetMapping("/settings/shortcuts")
    List<JsonNode> shortcuts() {
        return db.list("SELECT to_jsonb(s) - 'position' FROM shortcuts s ORDER BY position, id");
    }

    private static final String[] SHORTCUT_ICONS = {"drive", "youtube", "instagram", "tiktok", "landing", "mindmap", "link"};
    private static final String SHORTCUT = "SELECT to_jsonb(s) - 'position' FROM shortcuts s WHERE id = ?";

    @PutMapping("/settings/shortcuts")
    @Transactional
    List<JsonNode> saveShortcuts(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        db.lock(470005L);
        var shortcuts = Input.list(body, 100, Input::new);
        Input.unique(shortcuts.stream().map(shortcut -> shortcut.key("id")).toList());
        db.sql.update("DELETE FROM shortcuts WHERE NOT (id = ANY(?::text[]))", Store.array(shortcuts.stream().map(shortcut -> shortcut.key("id")).toList()));
        int position = 0;
        for (var shortcut : shortcuts) {
            String image = shortcut.has("image") ? shortcut.image("image") : null;
            db.sql.update("INSERT INTO shortcuts (id, label, url, icon, position, image) VALUES (?, ?, ?, ?, ?, COALESCE(?, '')) "
                    + "ON CONFLICT (id) DO UPDATE SET label=excluded.label, url=excluded.url, icon=excluded.icon, position=excluded.position, "
                    + "image=COALESCE(?, shortcuts.image)",
                shortcut.key("id"), shortcut.text("label", 1, 100), shortcut.url("url", true),
                shortcut.choice("icon", SHORTCUT_ICONS), position++, image, image);
        }
        return shortcuts();
    }

    /** Cria um atalho novo no fim da lista (Visão geral → Acesso rápido → Adicionar). */
    @PostMapping("/settings/shortcuts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode createShortcut(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        db.lock(470005L);
        var input = new Input(body);
        input.only("label", "url", "image", "icon");
        String label = input.has("label") ? input.text("label", 0, 100) : "";
        if (label.isEmpty()) label = "Novo atalho";
        String id = "sc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int count = db.sql.queryForObject("SELECT count(*) FROM shortcuts", Integer.class);
        if (count >= 100) throw ApiError.invalid("Limite de 100 atalhos atingido.");
        db.sql.update("INSERT INTO shortcuts (id, label, url, icon, position, image) VALUES (?, ?, ?, ?, (SELECT COALESCE(max(position) + 1, 0) FROM shortcuts), ?)",
            id, label, input.has("url") ? input.url("url", true) : "",
            input.has("icon") ? input.choice("icon", SHORTCUT_ICONS) : "link",
            input.has("image") ? input.image("image") : "");
        return db.first(SHORTCUT, id);
    }

    /** Edita nome, link, logo e/ou ícone de um atalho. Campos ausentes ficam como estão. */
    @PatchMapping("/settings/shortcuts/{id}")
    @Transactional
    JsonNode saveShortcut(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        actor.requireAdmin();
        db.lock(470005L);
        var input = new Input(body);
        input.only("label", "url", "image", "icon");
        if (db.first(SHORTCUT, id) == null) throw ApiError.missing("Atalho não encontrado.");
        String label = input.has("label") ? input.text("label", 1, 100) : null;
        String url = input.has("url") ? input.url("url", true) : null;
        String image = input.has("image") ? input.image("image") : null;
        String icon = input.has("icon") ? input.choice("icon", SHORTCUT_ICONS) : null;
        db.sql.update("UPDATE shortcuts SET label = COALESCE(?, label), url = COALESCE(?, url), image = COALESCE(?, image), icon = COALESCE(?, icon) WHERE id = ?",
            label, url, image, icon, id);
        return db.first(SHORTCUT, id);
    }

    @DeleteMapping("/settings/shortcuts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteShortcut(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireAdmin();
        db.lock(470005L);
        if (db.sql.update("DELETE FROM shortcuts WHERE id = ?", id) == 0) throw ApiError.missing("Atalho não encontrado.");
    }

    @GetMapping("/me/preferences")
    JsonNode preferences(@RequestAttribute Actor actor) {
        var value = db.first("SELECT preferences FROM member_preferences WHERE member_id = ?", actor.id());
        return value == null ? db.decode("{\"theme\":\"dark\",\"reducedMotion\":false,\"notifications\":{\"taskAssigned\":true,\"taskDue\":true,\"projectUpdates\":true,\"newsDigest\":false,\"email\":false}}") : value;
    }

    @PutMapping("/me/preferences")
    @Transactional
    JsonNode savePreferences(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        var input = new Input(body); input.only("theme", "reducedMotion", "notifications");
        input.choice("theme", "dark", "light", "system"); input.bool("reducedMotion");
        var alerts = new Input(body.path("notifications"));
        alerts.only("taskAssigned", "taskDue", "projectUpdates", "newsDigest", "email");
        for (String key : List.of("taskAssigned", "taskDue", "projectUpdates", "newsDigest", "email")) alerts.bool(key);
        if (alerts.bool("email")) throw ApiError.conflict("O envio por e-mail ainda não está configurado. As notificações estão disponíveis no portal.");
        db.sql.update("INSERT INTO member_preferences VALUES (?,?::jsonb) ON CONFLICT(member_id) DO UPDATE SET preferences=excluded.preferences", actor.id(), body.toString());
        return preferences(actor);
    }

    @GetMapping("/health")
    java.util.Map<String, String> health() {
        db.sql.queryForObject("SELECT 1", Integer.class);
        return java.util.Map.of("status", "ok");
    }
}
