package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.firewalldo.portal.SessionService.Actor;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Grouped URL shortcuts. Uploaded images are served separately, not embedded in lists or audit logs. */
@RestController
@RequestMapping("/api/driver")
class DriverController {
    private static final long LOCK=470011;
    private static final int MAX_IMAGE=250*1024;
    private static final String[] TYPES={"image/png","image/jpeg","image/webp"};
    private static final String FIELD="""
      SELECT jsonb_build_object('id',f.id,'name',f.name,'description',f.description,
        'image',CASE WHEN f.image_content IS NULL THEN NULL ELSE '/api/driver/fields/'||f.id||'/image?v='||floor(extract(epoch FROM f.updated_at)*1000)::bigint END,
        'createdBy',f.created_by,'createdAt',f.created_at,'updatedAt',f.updated_at,
        'shortcuts',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',s.id,'fieldId',s.field_id,'name',s.name,'url',s.url,
          'image',CASE WHEN s.image_content IS NULL THEN NULL ELSE '/api/driver/shortcuts/'||s.id||'/image?v='||floor(extract(epoch FROM s.updated_at)*1000)::bigint END,
          'createdBy',s.created_by,'createdAt',s.created_at,'updatedAt',s.updated_at) ORDER BY s.created_at,s.id)
          FROM driver_shortcuts s WHERE s.field_id=f.id),'[]'::jsonb)) FROM driver_fields f
      """;
    private static final String SHORTCUT="""
      SELECT jsonb_build_object('id',s.id,'fieldId',s.field_id,'name',s.name,'url',s.url,
        'image',CASE WHEN s.image_content IS NULL THEN NULL ELSE '/api/driver/shortcuts/'||s.id||'/image?v='||floor(extract(epoch FROM s.updated_at)*1000)::bigint END,
        'createdBy',s.created_by,'createdAt',s.created_at,'updatedAt',s.updated_at) FROM driver_shortcuts s
      """;
    private final Store db;
    DriverController(Store db){this.db=db;}
    @GetMapping List<JsonNode> list(){return db.list(FIELD+" ORDER BY lower(f.name),f.created_at,f.id");}
    @PostMapping("/fields") @ResponseStatus(HttpStatus.CREATED) @Transactional
    JsonNode createField(@RequestAttribute Actor actor,@RequestBody JsonNode body){
        actor.requireAdmin();db.lock(LOCK);var f=new Input(body);f.only("name","description","image");
        if(db.sql.queryForObject("SELECT count(*) FROM driver_fields",Integer.class)>=200)throw ApiError.invalid("Limite de 200 campos no Driver.");
        UUID id=UUID.randomUUID();Input.Upload image=image(f);
        db.sql.update("INSERT INTO driver_fields(id,name,description,image_content,image_type,created_by) VALUES(?,?,?,?,?,?)",id,f.text("name",2,120),f.optionalText("description",4000),image==null?null:image.bytes(),image==null?null:image.type(),actor.id());
        return field(id);
    }
    @PatchMapping("/fields/{id}") @Transactional
    JsonNode updateField(@RequestAttribute Actor actor,@PathVariable String id,@RequestBody JsonNode body){
        actor.requireAdmin();db.lock(LOCK);UUID uuid=Input.id(id);var current=field(uuid);var f=new Input(body);f.only("name","description","image");
        Input.Upload image=image(f);
        db.sql.update("UPDATE driver_fields SET name=?,description=?,image_content=CASE WHEN ? THEN ? ELSE image_content END,image_type=CASE WHEN ? THEN ? ELSE image_type END,updated_at=clock_timestamp() WHERE id=?",
          f.has("name")?f.text("name",2,120):current.path("name").asText(),f.has("description")?f.text("description",0,4000):current.path("description").asText(),
          f.has("image"),image==null?null:image.bytes(),f.has("image"),image==null?null:image.type(),uuid);
        return field(uuid);
    }
    @DeleteMapping("/fields/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    void deleteField(@RequestAttribute Actor actor,@PathVariable String id){
        actor.requireAdmin();db.lock(LOCK);if(db.sql.update("DELETE FROM driver_fields WHERE id=?",Input.id(id))==0)throw ApiError.missing("Campo não encontrado.");
    }
    @PostMapping("/fields/{id}/shortcuts") @ResponseStatus(HttpStatus.CREATED) @Transactional
    JsonNode createShortcut(@RequestAttribute Actor actor,@PathVariable String id,@RequestBody JsonNode body){
        actor.requireAdmin();db.lock(LOCK);UUID fieldId=Input.id(id);field(fieldId);var f=new Input(body);f.only("name","url","image");
        if(db.sql.queryForObject("SELECT count(*) FROM driver_shortcuts",Integer.class)>=2000)throw ApiError.invalid("Limite de 2.000 atalhos no Driver.");
        UUID uuid=UUID.randomUUID();Input.Upload image=image(f);
        db.sql.update("INSERT INTO driver_shortcuts(id,field_id,name,url,image_content,image_type,created_by) VALUES(?,?,?,?,?,?,?)",uuid,fieldId,f.text("name",2,120),f.url("url",false),image==null?null:image.bytes(),image==null?null:image.type(),actor.id());
        return shortcut(uuid);
    }
    @PatchMapping("/shortcuts/{id}") @Transactional
    JsonNode updateShortcut(@RequestAttribute Actor actor,@PathVariable String id,@RequestBody JsonNode body){
        actor.requireAdmin();db.lock(LOCK);UUID uuid=Input.id(id);var current=shortcut(uuid);var f=new Input(body);f.only("name","url","image");
        Input.Upload image=image(f);
        db.sql.update("UPDATE driver_shortcuts SET name=?,url=?,image_content=CASE WHEN ? THEN ? ELSE image_content END,image_type=CASE WHEN ? THEN ? ELSE image_type END,updated_at=clock_timestamp() WHERE id=?",
          f.has("name")?f.text("name",2,120):current.path("name").asText(),f.has("url")?f.url("url",false):current.path("url").asText(),
          f.has("image"),image==null?null:image.bytes(),f.has("image"),image==null?null:image.type(),uuid);
        return shortcut(uuid);
    }
    @DeleteMapping("/shortcuts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    void deleteShortcut(@RequestAttribute Actor actor,@PathVariable String id){
        actor.requireAdmin();db.lock(LOCK);if(db.sql.update("DELETE FROM driver_shortcuts WHERE id=?",Input.id(id))==0)throw ApiError.missing("Atalho não encontrado.");
    }
    @GetMapping("/fields/{id}/image") ResponseEntity<byte[]> fieldImage(@PathVariable String id){return serveImage("driver_fields",Input.id(id));}
    @GetMapping("/shortcuts/{id}/image") ResponseEntity<byte[]> shortcutImage(@PathVariable String id){return serveImage("driver_shortcuts",Input.id(id));}
    private JsonNode field(UUID id){return db.require("Campo não encontrado.",FIELD+" WHERE f.id=?",id);}
    private JsonNode shortcut(UUID id){return db.require("Atalho não encontrado.",SHORTCUT+" WHERE s.id=?",id);}
    private Input.Upload image(Input f){return !f.has("image")||f.text("image",0,350000).isEmpty()?null:f.upload("image",MAX_IMAGE,TYPES);}
    private ResponseEntity<byte[]> serveImage(String table,UUID id){
        var rows=db.sql.query("SELECT image_content,image_type FROM "+table+" WHERE id=? AND image_content IS NOT NULL",(row,index)->ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE,row.getString(2)).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'")
          .body(row.getBytes(1)),id);
        if(rows.isEmpty())throw ApiError.missing("Imagem não encontrada.");return rows.get(0);
    }
}
