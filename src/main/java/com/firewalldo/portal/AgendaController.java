package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firewalldo.portal.SessionService.Actor;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agenda")
class AgendaController {
    private final AgendaService service;
    private final Store db;
    AgendaController(AgendaService service,Store db){this.service=service;this.db=db;}

    @GetMapping
    List<JsonNode> list(@RequestAttribute Actor actor){
        var rows=actor.admin()?db.list(AgendaService.SELECT+" WHERE NOT s.deleted ORDER BY s.created_at DESC,s.id"):
          db.list(AgendaService.SELECT+" WHERE NOT s.deleted AND (s.config->'assigneeIds' @> ?::jsonb OR (s.config->>'initialStatus'='backlog' AND jsonb_array_length(s.config->'assigneeIds')=0)) ORDER BY s.created_at DESC,s.id","[\""+actor.id()+"\"]");
        for(JsonNode row:rows) {
            ObjectNode result=(ObjectNode)row;
            ObjectNode config=result.deepCopy();
            for(String key:List.of("id","active","createdBy","createdAt","updatedAt","nextRunAt"))config.remove(key);
            var rule=service.rule(config,false);
            var upcoming=result.putArray("upcoming");
            if(row.path("active").asBoolean() && !row.path("nextRunAt").isNull()) {
                Instant time=OffsetDateTime.parse(row.path("nextRunAt").asText()).toInstant();
                for(int i=0;i<6 && time!=null;i++) {upcoming.add(time.toString());time=rule.next(time,false);}
            }
        }
        return rows;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode create(@RequestAttribute Actor actor,@RequestBody JsonNode body){
        actor.requireAdmin();db.lock(AgendaService.TASK_LOCK);
        var rule=service.rule(body,true);
        if(db.sql.queryForObject("SELECT count(*) FROM task_schedules WHERE NOT deleted",Integer.class)>=1000)throw ApiError.invalid("Limite de 1.000 agendas. Remova agendas antigas antes de criar outras.");
        Instant next=rule.next(Instant.now(),true);
        if(next==null)throw ApiError.invalid("A agenda precisa ter ao menos uma abertura futura. Confira datas e horário de Brasília.");
        UUID id=UUID.randomUUID();
        db.sql.update("INSERT INTO task_schedules(id,title,config,created_by,next_run_at) VALUES(?,?,?::jsonb,?,?)",id,rule.title(),body.toString(),actor.id(),Timestamp.from(next));
        return get(id);
    }

    @PutMapping("/{id}")
    @Transactional
    JsonNode update(@RequestAttribute Actor actor,@PathVariable String id,@RequestBody JsonNode body){
        actor.requireAdmin();db.lock(AgendaService.TASK_LOCK);
        UUID uuid=Input.id(id);get(uuid);
        var rule=service.rule(body,true);
        Instant next=rule.next(Instant.now(),true);
        if(next==null)throw ApiError.invalid("Informe pelo menos uma abertura futura para salvar a agenda.");
        db.sql.update("UPDATE task_schedules SET title=?,config=?::jsonb,next_run_at=?,updated_at=now() WHERE id=?",rule.title(),body.toString(),Timestamp.from(next),uuid);
        return get(uuid);
    }

    @PatchMapping("/{id}/active")
    @Transactional
    JsonNode active(@RequestAttribute Actor actor,@PathVariable String id,@RequestBody JsonNode body){
        actor.requireAdmin();db.lock(AgendaService.TASK_LOCK);
        var f=new Input(body);f.only("active");boolean active=f.bool("active");
        UUID uuid=Input.id(id);var before=get(uuid);
        Instant next=null;
        if(active){var config=db.require("Agenda não encontrada.","SELECT config FROM task_schedules WHERE id=?",uuid);next=service.rule(config,true).next(Instant.now(),true);
            if(next==null)throw ApiError.invalid("Esta agenda terminou. Edite as datas antes de ativá-la.");}
        if(active!=before.path("active").asBoolean())db.sql.update("UPDATE task_schedules SET active=?,next_run_at=?,updated_at=now() WHERE id=?",active,next==null?null:Timestamp.from(next),uuid);
        return get(uuid);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@RequestAttribute Actor actor,@PathVariable String id){
        actor.requireAdmin();db.lock(AgendaService.TASK_LOCK);
        UUID uuid=Input.id(id);get(uuid);
        db.sql.update("UPDATE task_schedules SET deleted=true,active=false,next_run_at=null,updated_at=now() WHERE id=?",uuid);
    }
    private JsonNode get(UUID id){return db.require("Agenda não encontrada.",AgendaService.SELECT+" WHERE s.id=? AND NOT s.deleted",id);}
}
