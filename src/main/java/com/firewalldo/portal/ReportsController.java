package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.firewalldo.portal.SessionService.Actor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Reports read the durable audit trail, including deleted entities; never recreate history. */
@RestController
@RequestMapping("/api/reports")
class ReportsController {
    private final Store db;
    ReportsController(Store db){this.db=db;}
    private static final String EVENTS="""
      WITH events AS (SELECT a.*,CASE
        WHEN action='edicao' AND entity IN ('tasks','projects') AND details#>>'{changes,status,after}'='concluido'
          AND details#>>'{changes,status,before}' IS DISTINCT FROM 'concluido' THEN 'conclusao'
        ELSE action END AS operation FROM audit_logs a)
      """;
    private static final String ROW="""
      SELECT jsonb_build_object('id',id::text,'createdAt',created_at,'actorId',actor_id,'actorName',actor_name,
        'actorEmail',actor_email,'action',operation,'entity',entity,'entityId',entity_id,'targetName',target_name,
        'ip',ip,'outcome',outcome,'details',details) FROM events
      """;
    record Filter(String where,List<Object> args){}
    private Filter filter(String q,String action,String entity,String outcome,String actorId,LocalDate from,LocalDate to){
        if(q.length()>200 || !List.of("","criacao","edicao","exclusao","conclusao","finalizacao","login","logout","ativacao","acesso").contains(action)
          || !List.of("","sucesso","falha","bloqueado").contains(outcome))throw ApiError.invalid("Filtro inválido.");
        if(entity.length()>80 || !entity.matches("[a-z_]*"))throw ApiError.invalid("Módulo inválido.");
        if(from!=null && to!=null && from.isAfter(to))throw ApiError.invalid("A data inicial deve vir antes da final.");
        String where=" WHERE true";var args=new ArrayList<Object>();
        if(!q.isBlank()){where+=" AND concat_ws(' ',actor_name,actor_email,target_name,entity_id,entity,ip) ILIKE ?";args.add("%"+q.strip()+"%");}
        if(!action.isEmpty()){where+=" AND operation=?";args.add(action);}
        if(!entity.isEmpty()){where+=" AND entity=?";args.add(entity);}
        if(!outcome.isEmpty()){where+=" AND outcome=?";args.add(outcome);}
        if(actorId.equals("system"))where+=" AND actor_id IS NULL";
        else if(!actorId.isEmpty()){where+=" AND actor_id=?";args.add(Input.id(actorId));}
        if(from!=null){where+=" AND created_at >= (?::date::timestamp AT TIME ZONE 'America/Sao_Paulo')";args.add(from);}
        if(to!=null){where+=" AND created_at < ((?::date+1)::timestamp AT TIME ZONE 'America/Sao_Paulo')";args.add(to);}
        return new Filter(where,args);
    }
    @GetMapping
    Map<String,Object> list(@RequestAttribute Actor actor,@RequestParam(defaultValue="") String q,
      @RequestParam(defaultValue="") String action,@RequestParam(defaultValue="") String entity,
      @RequestParam(defaultValue="") String outcome,@RequestParam(defaultValue="") String actorId,
      @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
      @RequestParam(defaultValue="1") int page){
        actor.requireAdmin();if(page<1||page>100000)throw ApiError.invalid("Página inválida.");
        var f=filter(q,action,entity,outcome,actorId,from,to);
        var summary=db.first(EVENTS+"""
          SELECT jsonb_build_object('total',count(*),'creations',count(*) FILTER(WHERE operation='criacao'),
            'edits',count(*) FILTER(WHERE operation='edicao'),'deletions',count(*) FILTER(WHERE operation='exclusao'),
            'completions',count(*) FILTER(WHERE operation='conclusao'),'finalizations',count(*) FILTER(WHERE operation='finalizacao'))
          FROM events
          """+f.where,f.args.toArray());
        var args=new ArrayList<>(f.args);args.add((page-1)*50);
        var items=db.list(EVENTS+ROW+f.where+" ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",args.toArray());
        // Historical author names/emails survive account changes or deletion.
        var authors=db.list("SELECT jsonb_build_object('id',actor_id,'name',(array_agg(actor_name ORDER BY created_at DESC,id DESC))[1]) FROM audit_logs WHERE actor_id IS NOT NULL GROUP BY actor_id ORDER BY max(created_at) DESC LIMIT 1000");
        var modules=db.list("SELECT jsonb_build_object('id',entity,'count',count(*)) FROM audit_logs GROUP BY entity ORDER BY entity");
        return Map.of("items",items,"total",summary.path("total").asLong(),"page",page,"pageSize",50,"summary",summary,"authors",authors,"modules",modules);
    }
    @GetMapping("/export")
    ResponseEntity<byte[]> export(@RequestAttribute Actor actor,@RequestParam(defaultValue="") String q,
      @RequestParam(defaultValue="") String action,@RequestParam(defaultValue="") String entity,
      @RequestParam(defaultValue="") String outcome,@RequestParam(defaultValue="") String actorId,
      @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to){
        actor.requireAdmin();var f=filter(q,action,entity,outcome,actorId,from,to);
        Long total=db.sql.queryForObject(EVENTS+"SELECT count(*) FROM events"+f.where,Long.class,f.args.toArray());
        if(total!=null&&total>20000)throw ApiError.invalid("Há mais de 20.000 registros. Reduza o período ou aplique filtros para exportar, sem cortar o histórico.");
        var rows=db.list(EVENTS+ROW+f.where+" ORDER BY created_at DESC,id DESC LIMIT 20001",f.args.toArray());
        if(rows.size()>20000)throw ApiError.invalid("O histórico aumentou durante a exportação. Reduza o período e tente novamente.");
        var csv=new StringBuilder("\uFEFF");
        csv.append("Registro;Data e hora de Brasília;Autor;E-mail;Ação;Módulo;Nome do item;ID do item;Resultado;Campos alterados\r\n");
        var format=DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss").withZone(ZoneId.of("America/Sao_Paulo"));
        for(JsonNode row:rows){
            var fields=new ArrayList<String>();row.path("details").path("fields").forEach(n->fields.add(n.asText()));
            String[] values={row.path("id").asText(),format.format(OffsetDateTime.parse(row.path("createdAt").asText())),row.path("actorName").asText(),row.path("actorEmail").asText(),
              row.path("action").asText(),row.path("entity").asText(),row.path("targetName").asText(),row.path("entityId").asText(),row.path("outcome").asText(),String.join(", ",fields)};
            csv.append(Arrays.stream(values).map(ReportsController::cell).collect(java.util.stream.Collectors.joining(";"))).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=firewalldo-relatorios.csv")
          .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String cell(String value){
        // Spreadsheet formula injection must remain plain text, including leading whitespace.
        if(value.stripLeading().matches("(?s)^[=+@-].*")||value.startsWith("\t")||value.startsWith("\r")||value.startsWith("\n"))value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
}
