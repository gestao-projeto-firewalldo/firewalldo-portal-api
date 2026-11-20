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
          'readMinutes', n.read_minutes, 'featured', n.featured, 'cover', n.cover, 'isDemo', false, 'publicationStatus', n.publication_status, 'importedAt', n.imported_at, 'references', n.references_json, 'imageUrl', CASE WHEN n.image_content IS NOT NULL THEN '/api/news/'||n.id||'/image' ELSE NULL END, 'imageCredit', n.image_credit, 'imageSourceUrl', n.image_source_url, 'imageIllustrative', n.image_illustrative)
        FROM news n JOIN radar_sources s ON s.id = n.source_id AND s.active
        """;
    private final Store db;
    private final RadarImages images;

    ContentController(Store db, RadarImages images) { this.db = db; this.images=images; }

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
    List<JsonNode> news(@RequestAttribute Actor actor, @RequestParam(defaultValue="false") boolean drafts) {
        if(drafts)actor.requireAdmin();
        return db.list(NEWS + " WHERE n.publication_status=? ORDER BY n.featured DESC, n.published_at DESC, n.id", drafts?"draft":"published");
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
        if(form.has("imageUrl") && !body.path("imageUrl").asText().isBlank()) {
            var picture=images.download(form.url("imageUrl",false),form.has("imageCredit")?form.text("imageCredit",0,500):"Imagem vinculada pelo administrador",form.url("url",false),false);
            db.sql.update("UPDATE news SET image_content=?,image_type=?,image_credit=?,image_source_url=? WHERE id=?",picture.bytes(),picture.type(),picture.credit(),picture.sourceUrl(),id);
        }
        return db.first(NEWS + " WHERE n.id = ?", id);
    }

    @GetMapping("/news/saved")
    List<UUID> saved(@RequestAttribute Actor actor) {
        return db.sql.queryForList("""
            SELECT b.news_id FROM saved_news b JOIN news n ON n.id = b.news_id
            JOIN radar_sources s ON s.id = n.source_id AND s.active WHERE b.member_id = ? AND n.publication_status='published' ORDER BY b.news_id
            """, UUID.class, actor.id());
    }

    @PostMapping("/news/{id}/save")
    @Transactional
    List<UUID> toggleSaved(@RequestAttribute Actor actor, @PathVariable String id) {
        UUID newsId = Input.id(id);
        db.require("Notícia não encontrada.", NEWS + " WHERE n.id = ? AND n.publication_status='published' FOR UPDATE OF n", newsId);
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


    @PostMapping("/news/{id}/publish")
    JsonNode approve(@RequestAttribute Actor actor,@PathVariable String id) {
        actor.requireAdmin();
        if(db.sql.update("UPDATE news SET publication_status='published' WHERE id=? AND EXISTS(SELECT 1 FROM radar_sources WHERE id=news.source_id AND active)",Input.id(id))==0)throw ApiError.missing("Notícia ou fonte ativa não encontrada.");
        return db.first(NEWS+" WHERE n.id=?",Input.id(id));
    }
    @DeleteMapping("/news/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteNews(@RequestAttribute Actor actor,@PathVariable String id) {
        actor.requireAdmin();
        if(db.sql.update("DELETE FROM news WHERE id=?",Input.id(id))==0)throw ApiError.missing("Notícia não encontrada.");
    }
    @GetMapping("/news/{id}/image")
    org.springframework.http.ResponseEntity<byte[]> newsImage(@RequestAttribute Actor actor,@PathVariable String id) {
        var rows=db.sql.query("SELECT n.image_content,n.image_type FROM news n JOIN radar_sources s ON s.id=n.source_id AND s.active WHERE n.id=? AND n.image_content IS NOT NULL AND (n.publication_status='published' OR ?)",
          (r,i)->org.springframework.http.ResponseEntity.ok().header("Content-Type",r.getString(2)).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'").body(r.getBytes(1)),Input.id(id),actor.admin());
        if(rows.isEmpty())throw ApiError.missing("Imagem não encontrada.");return rows.get(0);
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
        db.sql.update("DELETE FROM radar_sources WHERE NOT (id = ANY(?::text[]))", Store.array(sources.stream().map(source -> source.key("id")).toList()));
        int position = 0;
        for (var source : sources) {
            PublicRadarNetwork.publicUrl(source.url("url",false));
            db.sql.update("INSERT INTO radar_sources VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET name=excluded.name, description=excluded.description, url=excluded.url, short=excluded.short, active=excluded.active, position=excluded.position", source.key("id"), source.text("name", 1, 100),
                source.text("description", 0, 1000), source.url("url", false), source.text("short", 1, 8),
                source.bool("active"), position++);
        }
        return sources();
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
