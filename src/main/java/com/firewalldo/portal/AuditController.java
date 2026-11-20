package com.firewalldo.portal;

import com.firewalldo.portal.SessionService.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
class AuditController {
    private final Store db;
    AuditController(Store db) { this.db = db; }
    @GetMapping
    Map<String,Object> list(@RequestAttribute Actor actor,
        @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String action,
        @RequestParam(defaultValue="") String entity, @RequestParam(defaultValue="") String outcome,
        @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
        @RequestParam(defaultValue="1") int page) {
        actor.requireAdmin();
        if (q.length() > 200 || page < 1 || page > 100000) throw ApiError.invalid("Filtro inválido.");
        if (!List.of("", "criacao","edicao","exclusao","conclusao","finalizacao","login","logout","ativacao","acesso").contains(action)
            || !List.of("","sucesso","falha","bloqueado").contains(outcome)) throw ApiError.invalid("Filtro inválido.");
        if (!entity.isEmpty() && !List.of("members","projects","tasks","ideas","idea_votes","news","saved_news","radar_sources","shortcuts","notifications","invitations","member_preferences","library_files","library_folders","autenticacao","radar_settings","radar_runs","push_subscriptions","push_deliveries","task_schedules","task_schedule_runs","member_photos","finance_investments","finance_payments").contains(entity)) throw ApiError.invalid("Módulo inválido.");
        if (from != null && to != null && from.isAfter(to)) throw ApiError.invalid("A data inicial deve vir antes da final.");
        String where = " WHERE true";
        var args = new ArrayList<Object>();
        if (!q.isBlank()) { where += " AND concat_ws(' ',actor_name,actor_email,target_name,entity_id,ip) ILIKE ?"; args.add("%" + q.strip() + "%"); }
        if (!action.isEmpty()) { where += " AND action = ?"; args.add(action); }
        if (!entity.isEmpty()) { where += " AND entity = ?"; args.add(entity); }
        if (!outcome.isEmpty()) { where += " AND outcome = ?"; args.add(outcome); }
        if (from != null) { where += " AND created_at >= (?::date::timestamp AT TIME ZONE 'America/Sao_Paulo')"; args.add(from); }
        if (to != null) { where += " AND created_at < ((?::date + 1)::timestamp AT TIME ZONE 'America/Sao_Paulo')"; args.add(to); }
        Long total = db.sql.queryForObject("SELECT count(*) FROM audit_logs" + where, Long.class, args.toArray());
        args.add((page - 1) * 50);
        List<JsonNode> items = db.list("""
            SELECT jsonb_build_object('id', id::text,'createdAt',created_at,'actorId',actor_id,'actorName',actor_name,
              'actorEmail',actor_email,'action',action,'entity',entity,'entityId',entity_id,'targetName',target_name,
              'ip',ip,'outcome',outcome,'details',details) FROM audit_logs
            """ + where + " ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?", args.toArray());
        return Map.of("items", items, "total", total == null ? 0 : total, "page",page,"pageSize",50);
    }
}
