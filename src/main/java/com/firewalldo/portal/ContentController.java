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
    private static final String NEWS = """
        SELECT jsonb_build_object('id', n.id, 'title', n.title, 'summary', n.summary, 'body', n.body,
          'category', n.category, 'source', s.name, 'url', n.url, 'publishedAt', n.published_at,
          'readMinutes', n.read_minutes, 'featured', n.featured, 'cover', n.cover, 'isDemo', false)
        FROM news n JOIN radar_sources s ON s.id = n.source_id AND s.active
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

    @GetMapping("/news")
    List<JsonNode> news() {
        return db.list(NEWS + " ORDER BY n.featured DESC, n.published_at DESC, n.id");
    }

    @PostMapping("/news")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode publish(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        var form = new Input(body);
        UUID id = UUID.randomUUID();
        String sourceId = form.key("sourceId");
        db.lock(470004L);
        db.require("Selecione uma fonte ativa.", "SELECT to_jsonb(id) FROM radar_sources WHERE id = ? AND active", sourceId);
        List<String> paragraphs = Input.list(body.path("body"), 30, node -> {
            if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > 5000 || node.textValue().indexOf(0) >= 0) {
                throw ApiError.invalid("Cada parágrafo deve ter de 1 a 5.000 caracteres.");
            }
            return node.textValue().strip();
        });
        if (paragraphs.isEmpty()) throw ApiError.invalid("Informe o texto da notícia.");
        db.sql.update("""
            INSERT INTO news (id, source_id, title, summary, body, category, url, published_at, read_minutes, featured, cover)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, sourceId, form.text("title", 3, 250), form.text("summary", 1, 1000), paragraphs.toArray(String[]::new),
            form.choice("category", "ciberseguranca", "redes", "informatica", "programacao", "ia", "privacidade"),
            form.url("url", false), form.date("publishedAt"), form.number("readMinutes", 1, 120),
            form.bool("featured"), form.choice("cover", "lock", "router", "chip", "ai", "code", "eye", "wifi", "bug"));
        return db.first(NEWS + " WHERE n.id = ?", id);
    }

    @GetMapping("/news/saved")
    List<UUID> saved(@RequestAttribute Actor actor) {
        return db.sql.queryForList("""
            SELECT b.news_id FROM saved_news b JOIN news n ON n.id = b.news_id
            JOIN radar_sources s ON s.id = n.source_id AND s.active WHERE b.member_id = ? ORDER BY b.news_id
            """, UUID.class, actor.id());
    }

    @PostMapping("/news/{id}/save")
    @Transactional
    List<UUID> toggleSaved(@RequestAttribute Actor actor, @PathVariable String id) {
        UUID newsId = Input.id(id);
        db.require("Notícia não encontrada.", NEWS + " WHERE n.id = ? FOR UPDATE OF n", newsId);
        if (db.sql.update("DELETE FROM saved_news WHERE news_id = ? AND member_id = ?", newsId, actor.id()) == 0) {
            db.sql.update("INSERT INTO saved_news VALUES (?, ?)", newsId, actor.id());
        }
        return saved(actor);
    }

    @GetMapping("/notifications")
    List<JsonNode> notifications(@RequestAttribute Actor actor) {
        return db.list("""
            SELECT jsonb_build_object('id', id, 'kind', kind, 'title', title, 'description', description,
              'createdAt', to_char(created_at AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI'), 'read', read)
            FROM notifications WHERE member_id = ? ORDER BY created_at DESC, id LIMIT 100
            """, actor.id());
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void readAll(@RequestAttribute Actor actor) {
        db.sql.update("UPDATE notifications SET read = true WHERE member_id = ? AND NOT read", actor.id());
    }

    @GetMapping("/settings/radar-sources")
    List<JsonNode> sources() {
        return db.list("SELECT to_jsonb(s) - 'position' FROM radar_sources s ORDER BY position, id");
    }

    @PutMapping("/settings/radar-sources")
    @Transactional
    List<JsonNode> saveSources(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        db.lock(470004L);
        var sources = Input.list(body, 100, Input::new);
        Input.unique(sources.stream().map(source -> source.key("id")).toList());
        db.sql.update("DELETE FROM radar_sources");
        int position = 0;
        for (var source : sources) {
            db.sql.update("INSERT INTO radar_sources VALUES (?, ?, ?, ?, ?, ?, ?)", source.key("id"), source.text("name", 1, 100),
                source.text("description", 0, 1000), source.url("url", false), source.text("short", 1, 8),
                source.bool("active"), position++);
        }
        return sources();
    }

    @GetMapping("/settings/shortcuts")
    List<JsonNode> shortcuts() {
        return db.list("SELECT to_jsonb(s) - 'position' FROM shortcuts s ORDER BY position, id");
    }

    @PutMapping("/settings/shortcuts")
    @Transactional
    List<JsonNode> saveShortcuts(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        db.lock(470005L);
        var shortcuts = Input.list(body, 100, Input::new);
        Input.unique(shortcuts.stream().map(shortcut -> shortcut.key("id")).toList());
        db.sql.update("DELETE FROM shortcuts");
        int position = 0;
        for (var shortcut : shortcuts) {
            db.sql.update("INSERT INTO shortcuts VALUES (?, ?, ?, ?, ?)", shortcut.key("id"), shortcut.text("label", 1, 100),
                shortcut.url("url", true), shortcut.choice("icon", "drive", "youtube", "instagram", "tiktok", "landing", "mindmap", "link"),
                position++);
        }
        return shortcuts();
    }

    @GetMapping({"/library/folders", "/library/files"})
    List<JsonNode> library() { return List.of(); }

    @GetMapping("/health")
    java.util.Map<String, String> health() {
        db.sql.queryForObject("SELECT 1", Integer.class);
        return java.util.Map.of("status", "ok");
    }
}
