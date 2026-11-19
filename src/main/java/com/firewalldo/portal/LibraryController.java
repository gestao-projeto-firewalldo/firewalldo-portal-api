package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.firewalldo.portal.SessionService.Actor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/library")
class LibraryController {
    private final Store db;
    private static final String FILE = """
      SELECT jsonb_build_object('id',f.id,'name',f.name,'folderId',f.folder_id,'kind',f.kind,
        'sizeBytes',octet_length(f.content),'updatedAt',to_char(f.created_at AT TIME ZONE 'America/Sao_Paulo','YYYY-MM-DD'),
        'ownerId',f.owner_id,'previewUrl','/api/library/files/'||f.id||'/content','tags',jsonb_build_array())
      FROM library_files f
      """;
    LibraryController(Store db) { this.db=db; }
    @GetMapping("/folders") List<JsonNode> folders() { return db.list("SELECT to_jsonb(f) FROM library_folders f ORDER BY name,id"); }
    @GetMapping("/files") List<JsonNode> files() { return db.list(FILE+" ORDER BY f.created_at DESC,f.id"); }
    @PostMapping("/files") @ResponseStatus(HttpStatus.CREATED) @Transactional
    JsonNode upload(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        var form = new Input(body);form.only("name","folderId","mediaType","content");
        String folder=form.key("folderId");
        db.require("Pasta não encontrada.","SELECT to_jsonb(id) FROM library_folders WHERE id=?",folder);
        String name=form.text("name",1,200);
        if (name.matches(".*[\\\\/\\r\\n].*")) throw ApiError.invalid("Nome de arquivo inválido.");
        String media=form.text("mediaType",0,100);
        if (!List.of("image/png","image/jpeg","image/webp","image/gif","application/pdf","video/mp4","video/webm","audio/mpeg","audio/wav","audio/ogg").contains(media)) media="application/octet-stream";
        String kind=media.startsWith("image/")?"image":media.startsWith("video/")?"video":media.startsWith("audio/")?"audio":media.equals("application/pdf")?"pdf":"other";
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(form.text("content",4,6990508)); }
        catch (IllegalArgumentException invalid) { throw ApiError.invalid("Conteúdo do arquivo inválido."); }
        if (bytes.length == 0 || bytes.length>5242880) throw ApiError.invalid("Envie um arquivo de até 5 MB.");
        UUID id=UUID.randomUUID();
        db.sql.update("INSERT INTO library_files(id,folder_id,name,kind,media_type,content,owner_id) VALUES(?,?,?,?,?,?,?)",id,folder,name,kind,media,bytes,actor.id());
        return db.first(FILE+" WHERE f.id=?",id);
    }
    @GetMapping("/files/{id}/content")
    ResponseEntity<byte[]> content(@PathVariable String id, @RequestParam(defaultValue="false") boolean download) {
        UUID uuid=Input.id(id);
        var files=db.sql.query("SELECT name,media_type,content FROM library_files WHERE id=?",(row,n)->new FileData(row.getString(1),row.getString(2),row.getBytes(3)),uuid);
        if (files.isEmpty()) throw ApiError.missing("Arquivo não encontrado.");
        var file=files.get(0);
        boolean attachment=download || file.media.equals("application/octet-stream");
        var disposition=(attachment?ContentDisposition.attachment():ContentDisposition.inline()).filename(file.name,StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.media)).contentLength(file.content.length)
            .header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy","frame-ancestors 'self'").body(file.content);
    }
    @DeleteMapping("/files/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    void remove(@RequestAttribute Actor actor,@PathVariable String id) {
        UUID uuid=Input.id(id);
        var file=db.require("Arquivo não encontrado.","SELECT jsonb_build_object('ownerId',owner_id) FROM library_files WHERE id=? FOR UPDATE",uuid);
        if (!actor.admin() && !file.path("ownerId").asText().equals(actor.id().toString())) throw ApiError.forbidden();
        db.sql.update("DELETE FROM library_files WHERE id=?",uuid);
    }
    private record FileData(String name,String media,byte[] content) {}
}
