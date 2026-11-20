package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.firewalldo.portal.SessionService.Actor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Radar de notícias V1.9: notícias cadastradas manualmente pela equipe.
 * Até 9 publicadas ao mesmo tempo: 1 principal, 3 secundárias e 5 gerais.
 * Os limites são conferidos dentro de uma transação com trava (advisory lock),
 * e o banco ainda impede duas principais por índice único.
 */
@RestController
@RequestMapping("/api")
class NewsController {
    static final long NEWS_LOCK = 470004L;
    static final Map<String, Integer> LIMITS = Map.of("principal", 1, "secundaria", 3, "geral", 5);
    static final String[] SLOTS = {"principal", "secundaria", "geral"};
    private static final String[] IMAGE_TYPES = {"image/png", "image/jpeg", "image/webp"};
    private static final int MAX_IMAGE = 2 * 1024 * 1024;
    static final String NEWS = """
        SELECT jsonb_build_object('id', n.id, 'title', n.title, 'summary', n.summary, 'body', n.body,
          'slot', n.slot, 'source', n.source_name, 'url', COALESCE(n.url, ''), 'publishedAt', n.published_at,
          'readMinutes', n.read_minutes, 'category', n.category, 'cover', n.cover, 'featured', n.slot = 'principal',
          'isDemo', false, 'updatedAt', n.updated_at,
          'imageUrl', CASE WHEN n.image_content IS NOT NULL
            THEN '/api/news/' || n.id || '/image?v=' || floor(extract(epoch FROM n.updated_at))::bigint ELSE NULL END)
        FROM news n
        """;
    private static final String ORDER = " ORDER BY CASE n.slot WHEN 'principal' THEN 0 WHEN 'secundaria' THEN 1 ELSE 2 END, n.published_at DESC, n.updated_at DESC, n.id";
    private final Store db;

    NewsController(Store db) { this.db = db; }

    @GetMapping("/news")
    List<JsonNode> list() {
        return db.list(NEWS + " WHERE n.slot IS NOT NULL AND n.publication_status = 'published'" + ORDER);
    }

    @PostMapping("/news")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode create(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        var form = new Input(body);
        form.only("title", "summary", "body", "slot", "image", "sourceName", "sourceUrl", "publishedAt");
        db.lock(NEWS_LOCK);
        String slot = form.choice("slot", SLOTS);
        requireVacancy(slot, null);
        Input.Upload image = form.has("image") && !body.path("image").asText("").isEmpty() ? form.upload("image", MAX_IMAGE, IMAGE_TYPES) : null;
        if (image == null && !slot.equals("geral")) throw ApiError.invalid("Envie a imagem de capa. Ela é obrigatória para notícias principais e secundárias.");
        List<String> paragraphs = paragraphs(form);
        UUID id = UUID.randomUUID();
        LocalDate published = form.has("publishedAt") && !body.path("publishedAt").isNull() ? form.date("publishedAt") : Input.today();
        String url = form.has("sourceUrl") ? form.url("sourceUrl", true) : "";
        db.sql.update("""
            INSERT INTO news (id, source_id, title, summary, body, category, url, published_at, read_minutes, featured, cover,
              publication_status, slot, source_name, created_by, image_content, image_type, updated_at)
            VALUES (?, NULL, ?, ?, ?, 'ciberseguranca', ?, ?, ?, ?, 'lock', 'published', ?, ?, ?, ?, ?, now())
            """, id, form.text("title", 3, 250), form.text("summary", 10, 600), paragraphs.toArray(String[]::new),
            url.isEmpty() ? null : url, published, readMinutes(paragraphs), slot.equals("principal"), slot,
            form.optionalText("sourceName", 120), actor.id(), image == null ? null : image.bytes(), image == null ? null : image.type());
        return db.first(NEWS + " WHERE n.id = ?", id);
    }

    @PatchMapping("/news/{id}")
    @Transactional
    JsonNode update(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        actor.requireAdmin();
        var form = new Input(body);
        form.only("title", "summary", "body", "slot", "image", "sourceName", "sourceUrl", "publishedAt");
        UUID newsId = Input.id(id);
        db.lock(NEWS_LOCK);
        var current = db.first("SELECT jsonb_build_object('slot', slot, 'hasImage', image_content IS NOT NULL) FROM news WHERE id = ? FOR UPDATE", newsId);
        if (current == null) throw ApiError.missing("Notícia não encontrada.");
        String oldSlot = current.path("slot").isNull() ? null : current.path("slot").asText();
        String slot = form.has("slot") ? form.choice("slot", SLOTS) : oldSlot;
        if (slot == null) throw ApiError.invalid("Escolha a categoria da notícia: principal, secundária ou geral.");
        if (!slot.equals(oldSlot)) requireVacancy(slot, newsId);

        boolean removeImage = form.has("image") && body.path("image").asText("").isEmpty();
        Input.Upload image = form.has("image") && !removeImage ? form.upload("image", MAX_IMAGE, IMAGE_TYPES) : null;
        boolean willHaveImage = image != null || (!removeImage && current.path("hasImage").asBoolean());
        if (!willHaveImage && !slot.equals("geral")) {
            throw ApiError.invalid("Notícias " + label(slot) + " precisam de imagem de capa. Envie uma imagem para continuar.");
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sets = new StringBuilder("slot = ?, featured = ?, updated_at = now()");
        args.add(slot); args.add(slot.equals("principal"));
        if (form.has("title")) { sets.append(", title = ?"); args.add(form.text("title", 3, 250)); }
        if (form.has("summary")) { sets.append(", summary = ?"); args.add(form.text("summary", 10, 600)); }
        if (form.has("body")) {
            var paragraphs = paragraphs(form);
            sets.append(", body = ?, read_minutes = ?"); args.add(paragraphs.toArray(String[]::new)); args.add(readMinutes(paragraphs));
        }
        if (form.has("sourceName")) { sets.append(", source_name = ?"); args.add(form.optionalText("sourceName", 120)); }
        if (form.has("sourceUrl")) { String url = form.url("sourceUrl", true); sets.append(", url = ?"); args.add(url.isEmpty() ? null : url); }
        if (form.has("publishedAt")) { sets.append(", published_at = ?"); args.add(form.date("publishedAt")); }
        if (image != null) { sets.append(", image_content = ?, image_type = ?"); args.add(image.bytes()); args.add(image.type()); }
        else if (removeImage) sets.append(", image_content = NULL, image_type = NULL");
        args.add(newsId);
        db.sql.update("UPDATE news SET " + sets + " WHERE id = ?", args.toArray());
        return db.first(NEWS + " WHERE n.id = ?", newsId);
    }

    @DeleteMapping("/news/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireAdmin();
        db.lock(NEWS_LOCK);
        if (db.sql.update("DELETE FROM news WHERE id = ?", Input.id(id)) == 0) throw ApiError.missing("Notícia não encontrada.");
    }

    @GetMapping("/news/{id}/image")
    ResponseEntity<byte[]> image(@PathVariable String id) {
        var rows = db.sql.query("SELECT image_content, image_type FROM news WHERE id = ? AND image_content IS NOT NULL",
            (row, index) -> ResponseEntity.ok().header("Content-Type", row.getString(2))
                .header("Cache-Control", "private, max-age=31536000, immutable")
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'")
                .body(row.getBytes(1)), Input.id(id));
        if (rows.isEmpty()) throw ApiError.missing("Imagem não encontrada.");
        return rows.get(0);
    }

    private void requireVacancy(String slot, UUID ignore) {
        int used = db.sql.queryForObject("SELECT count(*) FROM news WHERE slot = ? AND (?::uuid IS NULL OR id <> ?::uuid)", Integer.class, slot, ignore, ignore);
        if (used < LIMITS.get(slot)) return;
        throw ApiError.conflict(switch (slot) {
            case "principal" -> "Já existe uma notícia principal cadastrada. Exclua ou altere a categoria da notícia atual para liberar esta posição.";
            case "secundaria" -> "As 3 vagas de notícias secundárias estão ocupadas. Exclua ou altere a categoria de uma delas para liberar uma posição.";
            default -> "As 5 vagas de notícias gerais estão ocupadas. Exclua ou altere a categoria de uma delas para liberar uma posição.";
        });
    }

    private static String label(String slot) {
        return switch (slot) { case "principal" -> "principais"; case "secundaria" -> "secundárias"; default -> "gerais"; };
    }

    private static List<String> paragraphs(Input form) {
        String text = form.text("body", 20, 30_000).replace("\r\n", "\n");
        List<String> result = new ArrayList<>();
        for (String part : text.split("\n\\s*\n|\n")) if (!part.isBlank()) result.add(part.strip());
        if (result.isEmpty() || result.size() > 200) throw ApiError.invalid("Informe o texto completo da notícia.");
        return result;
    }

    private static int readMinutes(List<String> paragraphs) {
        int words = paragraphs.stream().mapToInt(p -> p.split("\\s+").length).sum();
        return Math.max(1, Math.min(120, (int) Math.ceil(words / 200.0)));
    }
}
