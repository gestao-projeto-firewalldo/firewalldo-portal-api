package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistent release cursor; all dates and opening times refer to Brasília. */
@Service
class AgendaService {
    static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");
    static final long TASK_LOCK=470003;
    static final String SELECT="""
      SELECT s.config || jsonb_build_object('id',s.id,'title',s.title,'active',s.active,
        'createdBy',s.created_by,'createdAt',s.created_at,'updatedAt',s.updated_at,'nextRunAt',s.next_run_at)
      FROM task_schedules s
      """;
    final Store db;
    final ObjectMapper json;
    AgendaService(Store db,ObjectMapper json){this.db=db;this.json=json;}

    record Rule(String title,String description,UUID project,String category,String priority,List<UUID> members,
                String kind,int interval,LocalDate start,LocalDate end,LocalTime time,List<Integer> weekdays,
                List<LocalDate> dates,int deadline,String checklist,String initialStatus,int maxAssignees) {
        Instant next(Instant from,boolean inclusive) {
            LocalDate first=from.atZone(ZONE).toLocalDate();
            if(first.isBefore(start))first=start;
            if(kind.equals("scheduled")) {
                for(LocalDate date:dates) {
                    Instant value=date.atTime(time).atZone(ZONE).toInstant();
                    if((inclusive?!value.isBefore(from):value.isAfter(from)))return value;
                }
                return null;
            }
            // A recurring interval is <=365 days; weekly rules must select a day.
            for(int i=0;i<=366;i++) {
                LocalDate date=first.plusDays(i);
                if(end!=null && date.isAfter(end))return null;
                boolean matches=kind.equals("weekly")?weekdays.contains(date.getDayOfWeek().getValue()):
                    java.time.temporal.ChronoUnit.DAYS.between(start,date)%interval==0;
                Instant value=date.atTime(time).atZone(ZONE).toInstant();
                if(matches && (inclusive?!value.isBefore(from):value.isAfter(from)))return value;
            }
            return null;
        }
    }

    Rule rule(JsonNode body,boolean validateMembers) {
        var f=new Input(body);
        f.only("title","description","projectId","category","priority","assigneeIds","kind","intervalDays",
          "startDate","endDate","openingTime","weekdays","dates","deadlineDays","checklist","initialStatus","maxAssignees");
        var members=f.ids("assigneeIds");
        String status=f.has("initialStatus")?f.choice("initialStatus","backlog","a_fazer"):"a_fazer";
        int max=f.has("maxAssignees")?f.number("maxAssignees",1,100):Math.max(1,members.size());
        if(status.equals("a_fazer") && members.isEmpty())throw ApiError.invalid("Vincule pelo menos uma pessoa ou escolha Backlog para receber aceites.");
        if(status.equals("a_fazer"))max=Math.max(max,members.size());
        if(validateMembers)db.validateMembers(members);
        UUID project=f.optionalId("projectId");
        if(project!=null && validateMembers)db.require("Projeto não encontrado.","SELECT to_jsonb(id) FROM projects WHERE id=?",project);
        String kind=f.choice("kind","recurring","weekly","scheduled");
        LocalDate start=f.date("startDate"),end=f.optionalDate("endDate");
        if(start.getYear()<2000 || start.getYear()>2100 || (end!=null && (end.isBefore(start)||end.getYear()>2100)))throw ApiError.invalid("Confira o início e o fim da agenda (2000 a 2100).");
        LocalTime time;
        try {String value=f.text("openingTime",5,5);if(!value.matches("[0-2][0-9]:[0-5][0-9]"))throw new DateTimeException("time");time=LocalTime.parse(value);}
        catch(DateTimeException error){throw ApiError.invalid("Informe um horário válido.");}
        int interval=f.number("intervalDays",1,365);
        List<Integer> days=Input.unique(Input.list(body.path("weekdays"),7,n->{if(!n.isInt()||n.intValue()<1||n.intValue()>7)throw ApiError.invalid("Dia da semana inválido.");return n.intValue();}));
        if(kind.equals("weekly")&&days.isEmpty())throw ApiError.invalid("Selecione pelo menos um dia da semana.");
        List<LocalDate> dates=!kind.equals("scheduled")?List.of():Input.unique(Input.list(body.path("dates"),100,n->{try {LocalDate d=LocalDate.parse(n.asText());if(d.isBefore(start)||(end!=null&&d.isAfter(end))||d.getYear()>2100)throw new DateTimeException("date");return d;}catch(DateTimeException error){throw ApiError.invalid("Confira as datas selecionadas e o período da agenda.");}})).stream().sorted().toList();
        if(kind.equals("scheduled")&&dates.isEmpty())throw ApiError.invalid("Selecione pelo menos uma data.");
        var checklist=json.createArrayNode();
        var keys=new ArrayList<String>();
        for(var item:Input.list(body.path("checklist"),100,Input::new)) {
            String id=item.key("id");keys.add(id);
            checklist.addObject().put("id",id).put("text",item.text("text",1,500)).put("done",false);
        }
        Input.unique(keys);
        return new Rule(f.text("title",3,200),f.text("description",0,12000),project,f.choice("category",Input.AREAS),
          f.choice("priority","baixa","media","alta","critica"),members,kind,interval,start,end,time,days,dates,
          f.number("deadlineDays",0,365),checklist.toString(),status,max);
    }

    @Scheduled(initialDelayString="${AGENDA_INITIAL_DELAY_MS:15000}",fixedDelayString="${AGENDA_INTERVAL_MS:15000}")
    @Transactional
    public void tick(){releaseDue(Instant.now());}

    /** Also used by deterministic integration tests. One bounded batch; backlog resumes next tick. */
    @Transactional
    public int releaseDue(Instant now) {
        db.lock(TASK_LOCK);
        var due=db.list("""
          SELECT jsonb_build_object('id',id,'config',config,'createdBy',created_by,'nextRunAt',next_run_at)
          FROM task_schedules WHERE active AND NOT deleted AND next_run_at<=?
          ORDER BY next_run_at,id LIMIT 5 FOR UPDATE
          """,Timestamp.from(now));
        int created=0;
        for(var schedule:due) {
            Rule r=rule(schedule.path("config"),false);
            UUID scheduleId=Input.id(schedule.path("id").asText()),creator=Input.id(schedule.path("createdBy").asText());
            Instant release=OffsetDateTime.parse(schedule.path("nextRunAt").asText()).toInstant();
            var active=db.sql.queryForList("SELECT id FROM members WHERE active AND id=ANY(?::uuid[]) ORDER BY id FOR SHARE",UUID.class,Store.array(r.members));
            for(int batch=0;batch<20 && release!=null && !release.isAfter(now);batch++) {
                LocalDate date=release.atZone(ZONE).toLocalDate();
                boolean backlog=r.initialStatus.equals("backlog");
                // One occurrence for the whole team, not a random/individual recipient.
                // A previously released legacy occurrence also counts, even if its task was deleted.
                if(backlog || !active.isEmpty()) {
                    UUID runId=UUID.randomUUID(),task=UUID.randomUUID();
                    int inserted=db.sql.update("""
                      INSERT INTO task_schedule_runs(id,schedule_id,scheduled_for,member_id)
                      SELECT ?,?,?,NULL WHERE NOT EXISTS
                        (SELECT 1 FROM task_schedule_runs WHERE schedule_id=? AND scheduled_for=?)
                      ON CONFLICT DO NOTHING
                      """,runId,scheduleId,Timestamp.from(release),scheduleId,Timestamp.from(release));
                    if(inserted>0) {
                        db.sql.update("""
                          INSERT INTO tasks(id,title,description,project_id,category,status,priority,assignee_ids,eligible_assignee_ids,open,max_assignees,
                            due_date,checklist,created_by,created_at,updated_at,position,schedule_id,scheduled_for,schedule_member_id)
                          VALUES(?,?,?,?,?,?,?,?::uuid[],?::uuid[],?,?,?,?::jsonb,?,?,?,
                            (SELECT COALESCE(max(position),0)+1 FROM tasks WHERE status=?),?,?,NULL)
                          """,task,r.title,r.description,r.project,r.category,r.initialStatus,r.priority,
                          Store.array(backlog?List.of():active),Store.array(backlog?r.members:List.of()),backlog,r.maxAssignees,date.plusDays(r.deadline),
                          r.checklist,creator,date,now.atZone(ZONE).toLocalDate(),r.initialStatus,scheduleId,Timestamp.from(release));
                        db.sql.update("UPDATE task_schedule_runs SET task_id=? WHERE id=?",task,runId);
                        db.sql.update("""
                          INSERT INTO notifications(id,member_id,kind,title,description,dedup_key)
                          SELECT gen_random_uuid(),m.id,'tarefa',?,?,'agenda:'||?||':'||m.id FROM members m
                          WHERE m.active AND (m.id=ANY(?::uuid[]) OR (? AND cardinality(?::uuid[])=0))
                            AND COALESCE((SELECT (preferences#>>'{notifications,taskAssigned}')::boolean FROM member_preferences WHERE member_id=m.id),true)
                          ON CONFLICT(dedup_key) DO NOTHING
                          """,backlog?"Tarefa da agenda disponível para aceitar":"Tarefa da agenda liberada",r.title,runId.toString(),
                          Store.array(backlog?r.members:active),backlog,Store.array(r.members));
                        created++;
                    }
                }
                release=r.next(release,false);
            }
            db.sql.update("UPDATE task_schedules SET next_run_at=? WHERE id=?",release==null?null:Timestamp.from(release),scheduleId);
        }
        return created;
    }
}
