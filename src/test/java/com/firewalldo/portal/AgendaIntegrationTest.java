package com.firewalldo.portal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"SESSION_SECURE=false","ADMIN_EMAIL=agenda@example.test","ADMIN_PASSWORD=agenda-test-password","AGENDA_INITIAL_DELAY_MS=3600000","NOTIFICATION_INITIAL_DELAY_MS=3600000"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgendaIntegrationTest {
    static final EmbeddedPostgres PG;
    static {try{PG=EmbeddedPostgres.builder().setServerConfig("unix_socket_directories","").setPort(0).start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate sql;@Autowired AgendaService agenda;
    Cookie king,admin,user,other;String kingId,adminId,userId,otherId;
    LocalDate start;
    JsonNode call(MockHttpServletRequestBuilder req,Cookie cookie,Object body,int status)throws Exception{
        req.contentType("application/json");if(cookie!=null)req.cookie(cookie);if(body!=null)req.content(json.writeValueAsBytes(body));
        var res=mvc.perform(req).andReturn().getResponse();assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        return res.getContentAsByteArray().length==0?json.nullNode():json.readTree(res.getContentAsByteArray());
    }
    Cookie login(String email,String password)throws Exception{return mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsBytes(Map.of("email",email,"senha",password)))).andReturn().getResponse().getCookie("fw_session");}
    String member(String email,String role)throws Exception{return call(post("/api/users"),king,Map.of("name",email,"email",email,"role",role,"title","","senha","member-test-password"),201).path("id").asText();}
    @BeforeAll void accounts()throws Exception{
        king=login("agenda@example.test","agenda-test-password");kingId=call(get("/api/auth/me"),king,null,200).path("id").asText();
        adminId=member("agenda-admin@example.test","admin");userId=member("agenda-user@example.test","usuario");otherId=member("agenda-other@example.test","usuario");
        admin=login("agenda-admin@example.test","member-test-password");user=login("agenda-user@example.test","member-test-password");other=login("agenda-other@example.test","member-test-password");
    }
    @BeforeEach void reset(){sql.update("UPDATE task_schedules SET active=false,next_run_at=null");start=Input.today().plusDays(2);}
    ObjectNode form(String kind){var body=json.createObjectNode().put("title","Rotina de segurança "+UUID.randomUUID()).put("description","Cada pessoa deve verificar seus próprios alertas.").putNull("projectId").put("category","infraestrutura").put("priority","alta").put("kind",kind).put("intervalDays",1).put("startDate",start.toString()).putNull("endDate").put("openingTime","09:15").put("deadlineDays",2);
        body.putArray("assigneeIds").add(userId).add(otherId);body.putArray("weekdays").add(start.getDayOfWeek().getValue());body.putArray("dates").add(start.toString()).add(start.plusDays(3).toString());body.putArray("checklist").addObject().put("id","check-1").put("text","Verificar alertas").put("done",false);return body;}
    Instant at(LocalDate d){return d.atTime(9,15).atZone(AgendaService.ZONE).toInstant();}
    List<JsonNode> tasks(String id,Cookie actor)throws Exception{List<JsonNode> list=new ArrayList<>();call(get("/api/tasks"),actor,null,200).forEach(t->{if(t.path("scheduleId").asText().equals(id))list.add(t);});return list;}
    String create(ObjectNode f)throws Exception{return call(post("/api/agenda"),admin,f,201).path("id").asText();}

    @Test void releasesOnlyAtOpeningWithTheWholeTeamAndSharedProgress()throws Exception{
        String id=create(form("scheduled"));assertThat(agenda.releaseDue(at(start).minusSeconds(1))).isZero();assertThat(tasks(id,king)).isEmpty();
        assertThat(agenda.releaseDue(at(start))).isEqualTo(1);var all=tasks(id,king);assertThat(all).hasSize(1);
        var mine=tasks(id,user).get(0);var theirs=tasks(id,other).get(0);assertThat(mine.path("id")).isEqualTo(theirs.path("id"));
        assertThat(new Input(mine).ids("assigneeIds")).containsExactlyInAnyOrder(UUID.fromString(userId),UUID.fromString(otherId));
        assertThat(mine.path("dueDate").asText()).isEqualTo(start.plusDays(2).toString());assertThat(mine.path("scheduleIndividual").asBoolean()).isFalse();
        call(patch("/api/tasks/"+mine.path("id").asText()),user,Map.of("status","em_andamento"),200);
        call(patch("/api/tasks/"+mine.path("id").asText()),other,Map.of("status","concluido","checklist",List.of(Map.of("id","check-1","text","Verificar alertas","done",true))),200);
        assertThat(tasks(id,user).get(0).path("status").asText()).isEqualTo("concluido");assertThat(tasks(id,user).get(0).path("checklist").get(0).path("done").asBoolean()).isTrue();
        call(get("/api/tasks").param("assigneeId",otherId),user,null,403);call(get("/api/tasks").param("assigneeId",otherId),admin,null,200);
        call(patch("/api/tasks/"+mine.path("id").asText()),admin,Map.of("title","Editar apenas esta ocorrência"),200);
        call(patch("/api/tasks/"+mine.path("id").asText()),admin,Map.of("assigneeIds",List.of(otherId)),200);
    }
    @Test void retryConcurrentWorkersAndDeletedTaskDoNotDuplicate()throws Exception{
        String id=create(form("scheduled"));var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->agenda.releaseDue(at(start)));var b=pool.submit(()->agenda.releaseDue(at(start)));assertThat(a.get()+b.get()).isEqualTo(1);}finally{pool.shutdownNow();}
        String task=tasks(id,king).get(0).path("id").asText();call(delete("/api/tasks/"+task),admin,null,204);
        sql.update("UPDATE task_schedules SET next_run_at=? WHERE id=?",Timestamp.from(at(start)),UUID.fromString(id));
        assertThat(agenda.releaseDue(at(start))).isZero();assertThat(tasks(id,king)).isEmpty();
        assertThat(agenda.releaseDue(at(start.plusDays(3)))).isEqualTo(1);
    }
    @Test void dailyIntervalWeeklyAndScheduledRulesUseBrasiliaAndCatchUp()throws Exception{
        ObjectNode daily=form("recurring");daily.put("intervalDays",2);String id=create(daily);
        assertThat(agenda.releaseDue(at(start.plusDays(5)))).isEqualTo(3);assertThat(tasks(id,king)).hasSize(3);
        assertThat(sql.queryForObject("SELECT next_run_at FROM task_schedules WHERE id=?",Timestamp.class,UUID.fromString(id)).toInstant()).isEqualTo(at(start.plusDays(6)));
        sql.update("UPDATE task_schedules SET active=false");String weekly=create(form("weekly"));
        assertThat(agenda.releaseDue(at(start.plusDays(6)))).isEqualTo(1);assertThat(tasks(weekly,king)).hasSize(1);
        assertThat(agenda.releaseDue(at(start.plusDays(7)))).isEqualTo(1);
        assertThat(at(start).atOffset(ZoneOffset.UTC).getHour()).isEqualTo(12);
    }
    @Test void editPauseResumeAndDeletePreserveReleasedTasks()throws Exception{
        ObjectNode f=form("scheduled");String id=create(f);agenda.releaseDue(at(start));
        f.put("title","Nova descrição da regra");f.putArray("assigneeIds").add(userId);call(put("/api/agenda/"+id),king,f,200);
        agenda.releaseDue(at(start));assertThat(tasks(id,king)).hasSize(1);
        call(patch("/api/agenda/"+id+"/active"),admin,Map.of("active",false),200);assertThat(agenda.releaseDue(at(start.plusDays(3)))).isZero();
        call(patch("/api/agenda/"+id+"/active"),king,Map.of("active",true),200);
        assertThat(agenda.releaseDue(at(start.plusDays(3)))).isEqualTo(1);assertThat(tasks(id,king)).hasSize(2);
        call(delete("/api/agenda/"+id),admin,null,204);assertThat(tasks(id,king)).hasSize(2);
        assertThat(call(get("/api/agenda"),admin,null,200).toString()).doesNotContain(id);
        assertThat(agenda.releaseDue(at(start.plusDays(10)))).isZero();
    }
    @Test void rejectsInvalidRulesAndNormalUsersCannotManageOrSeeForeignAgenda()throws Exception{
        ObjectNode f=form("weekly");f.putArray("weekdays");call(post("/api/agenda"),king,f,400);
        f=form("scheduled");f.put("openingTime","29:15");call(post("/api/agenda"),king,f,400);
        f=form("recurring");f.put("endDate",start.minusDays(1).toString());call(post("/api/agenda"),king,f,400);
        f=form("scheduled");f.putArray("dates").add("2026-02-30");call(post("/api/agenda"),king,f,400);
        f=form("recurring");call(post("/api/agenda"),user,f,403);String id=create(f);
        call(put("/api/agenda/"+id),user,f,403);call(delete("/api/agenda/"+id),user,null,403);call(patch("/api/agenda/"+id+"/active"),user,Map.of("active",false),403);
        f=form("scheduled");f.putArray("assigneeIds").add(adminId);String foreign=create(f);
        var own=call(get("/api/agenda"),user,null,200);assertThat(own.toString()).contains(id).doesNotContain(foreign);
        var selected=java.util.stream.StreamSupport.stream(own.spliterator(),false).filter(n->n.path("id").asText().equals(id)).findFirst().orElseThrow();
        assertThat(selected.path("assigneeIds").size()).isEqualTo(2);assertThat(selected.path("upcoming").size()).isEqualTo(6);
    }
    @Test void notificationAndSystemAuditArePersistedAndInactiveRecipientsAreSkipped()throws Exception{
        String id=create(form("scheduled"));sql.update("UPDATE members SET active=false WHERE id=?",UUID.fromString(otherId));
        try{assertThat(agenda.releaseDue(at(start))).isEqualTo(1);}finally{sql.update("UPDATE members SET active=true WHERE id=?",UUID.fromString(otherId));}
        assertThat(sql.queryForObject("SELECT count(*) FROM notifications WHERE dedup_key LIKE 'agenda:%' AND description=(SELECT title FROM task_schedules WHERE id=?)",Integer.class,UUID.fromString(id))).isEqualTo(1);
        String task=tasks(id,king).get(0).path("id").asText();var audit=call(get("/api/audit").param("entity","tasks").param("q",task),king,null,200);
        assertThat(audit.path("items").get(0).path("actorName").asText()).isEqualTo("Sistema");assertThat(audit.path("items").get(0).path("ip").isNull()).isTrue();
        var changes=call(get("/api/audit").param("entity","task_schedules").param("q",id),admin,null,200);assertThat(changes.path("total").asInt()).isGreaterThan(0);
    }
    @Test void endDateStopsRecurrenceAndLargeBacklogRemainsBounded()throws Exception{
        ObjectNode f=form("recurring");f.put("endDate",start.plusDays(1).toString());String id=create(f);
        assertThat(agenda.releaseDue(at(start.plusDays(20)))).isEqualTo(2);assertThat(tasks(id,king)).hasSize(2);assertThat(sql.queryForObject("SELECT next_run_at IS NULL FROM task_schedules WHERE id=?",Boolean.class,UUID.fromString(id))).isTrue();
        f=form("recurring");String back=create(f);assertThat(agenda.releaseDue(at(start.plusDays(30)))).isEqualTo(20);assertThat(tasks(back,king)).hasSize(20);
        assertThat(agenda.releaseDue(at(start.plusDays(30)))).isEqualTo(11);assertThat(tasks(back,king)).hasSize(31);
    }
    @Test void globalTicketsFollowLaunchOrderAcrossManualAndAgendaAndDoNotChangeOnEdit()throws Exception{
        long before=sql.queryForObject("SELECT last_number FROM task_ticket_counter",Long.class);
        var manual=json.createObjectNode().put("title","Primeiro ticket manual").put("description","").putNull("projectId").put("category","gestao").put("status","a_fazer").put("priority","media").put("open",false).put("maxAssignees",1).putNull("dueDate");manual.putArray("assigneeIds").add(userId);manual.putArray("checklist");
        var first=call(post("/api/tasks"),admin,manual,201);assertThat(first.path("ticketNumber").asLong()).isEqualTo(before+1);
        call(patch("/api/tasks/"+first.path("id").asText()),admin,Map.of("title","Ticket editado"),200);
        call(patch("/api/tasks/"+first.path("id").asText()),user,Map.of("status","em_andamento"),200);
        var unchanged=call(patch("/api/tasks/"+first.path("id").asText()),user,Map.of("status","concluido"),200);assertThat(unchanged.path("ticketNumber").asLong()).isEqualTo(before+1);
        call(patch("/api/tasks/"+first.path("id").asText()),admin,Map.of("ticketNumber",9000),400);
        manual.put("title","x");call(post("/api/tasks"),admin,manual,400);assertThat(sql.queryForObject("SELECT last_number FROM task_ticket_counter",Long.class)).isEqualTo(before+1);
        String id=create(form("scheduled"));assertThat(sql.queryForObject("SELECT last_number FROM task_ticket_counter",Long.class)).isEqualTo(before+1);
        agenda.releaseDue(at(start));assertThat(tasks(id,king).stream().map(t->t.path("ticketNumber").asLong()).sorted().toList()).containsExactly(before+2);
        agenda.releaseDue(at(start.plusDays(3)));assertThat(tasks(id,king).stream().map(t->t.path("ticketNumber").asLong()).sorted().toList()).containsExactly(before+2,before+3);
        call(delete("/api/tasks/"+first.path("id").asText()),admin,null,204);manual.put("title","Último ticket manual");assertThat(call(post("/api/tasks"),admin,manual,201).path("ticketNumber").asLong()).isEqualTo(before+4);
    }
    @Test void migratesExistingTasksAndCounterRollbacksDoNotConsumeTickets()throws Exception{
        try(var pg=EmbeddedPostgres.builder().setServerConfig("unix_socket_directories","").setPort(0).start()) {
            var ds=pg.getPostgresDatabase();var local=new JdbcTemplate(ds);
            org.flywaydb.core.Flyway.configure().dataSource(ds).target("5").load().migrate();
            UUID person=UUID.randomUUID();local.update("INSERT INTO members(id,name,email,role) VALUES(?,'Legacy','legacy@example.test','usuario')",person);
            String insert="INSERT INTO tasks(id,title,description,category,status,priority,assignee_ids,open,max_assignees,checklist,created_by,created_at,updated_at,position) VALUES(gen_random_uuid(),?,'','gestao','a_fazer','media','{}',false,1,'[]',?,?,?,1)";
            local.update(insert,"Later",person,LocalDate.of(2026,11,18),LocalDate.of(2026,11,18));local.update(insert,"Earlier",person,LocalDate.of(2026,11,17),LocalDate.of(2026,11,17));
            org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();
            assertThat(local.queryForObject("SELECT ticket_number FROM tasks WHERE title='Earlier'",Long.class)).isEqualTo(1);
            assertThat(local.queryForObject("SELECT ticket_number FROM tasks WHERE title='Later'",Long.class)).isEqualTo(2);
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            tx.executeWithoutResult(status->{local.update(insert,"Rolled back",person,start,start);status.setRollbackOnly();});
            local.update(insert,"New launch",person,start,start);assertThat(local.queryForObject("SELECT ticket_number FROM tasks WHERE title='New launch'",Long.class)).isEqualTo(3);
            assertThatThrownBy(()->local.update("UPDATE tasks SET ticket_number=900 WHERE title='New launch'")).hasMessageContaining("não pode ser alterado");
        }
    }

    ObjectNode backlog(String kind,int max){var f=form(kind);f.put("initialStatus","backlog").put("maxAssignees",max);f.withArray("assigneeIds").add(adminId);return f;}
    JsonNode accept(String task,Cookie person,int status)throws Exception{return call(post("/api/tasks/"+task+"/accept"),person,null,status);}
    @Test void threeCandidatesOneSlotStartsEmptyAndFirstAcceptanceWins()throws Exception{
        String id=create(backlog("recurring",1));assertThat(agenda.releaseDue(at(start))).isEqualTo(1);var task=tasks(id,king).get(0);String tid=task.path("id").asText();
        assertThat(task.path("status").asText()).isEqualTo("backlog");assertThat(task.path("assigneeIds")).isEmpty();assertThat(task.path("eligibleAssigneeIds")).hasSize(3);assertThat(task.path("open").asBoolean()).isTrue();
        assertThat(tasks(id,user)).hasSize(1);assertThat(tasks(id,other)).hasSize(1);
        call(patch("/api/tasks/"+tid+"/move"),king,Map.of("status","a_fazer"),400);
        var won=accept(tid,user,200);assertThat(won.path("status").asText()).isEqualTo("a_fazer");assertThat(new Input(won).ids("assigneeIds")).containsExactly(UUID.fromString(userId));
        accept(tid,other,409);assertThat(tasks(id,other)).isEmpty();assertThat(accept(tid,user,200).path("assigneeIds")).hasSize(1);
    }
    @Test void weeklyAndDatedBacklogWaitForSecondAcceptanceAndKeepCandidatesSeparate()throws Exception{
        for(String kind:List.of("weekly","scheduled")){
            sql.update("UPDATE task_schedules SET active=false");String id=create(backlog(kind,2));assertThat(agenda.releaseDue(at(start))).isEqualTo(1);
            String tid=tasks(id,king).get(0).path("id").asText();
            var one=accept(tid,user,200);assertThat(one.path("status").asText()).isEqualTo("backlog");assertThat(one.path("assigneeIds")).hasSize(1);assertThat(one.path("open").asBoolean()).isTrue();
            call(patch("/api/tasks/"+tid),admin,Map.of("title","Editar ocorrência sem perder o aceite"),200);
            call(patch("/api/tasks/"+tid),admin,Map.of("assigneeIds",List.of(userId,otherId)),400);
            var two=accept(tid,other,200);assertThat(two.path("status").asText()).isEqualTo("a_fazer");assertThat(two.path("assigneeIds")).hasSize(2);assertThat(two.path("eligibleAssigneeIds")).hasSize(3);
            accept(tid,admin,409);call(patch("/api/tasks/"+tid+"/move"),admin,Map.of("status","em_andamento"),403);
            call(patch("/api/tasks/"+tid+"/move"),user,Map.of("status","em_andamento"),204);
        }
    }
    @Test void backlogForAnyoneIsVisibleAndRestrictedInvitesDoNotIncludeUninvitedAdmins()throws Exception{
        var f=backlog("scheduled",2);f.putArray("assigneeIds");String id=create(f);agenda.releaseDue(at(start));String tid=tasks(id,king).get(0).path("id").asText();
        assertThat(call(get("/api/agenda"),user,null,200).toString()).contains(id);assertThat(tasks(id,user)).hasSize(1);
        accept(tid,user,200);accept(tid,other,200);
        sql.update("UPDATE task_schedules SET active=false");f=backlog("scheduled",1);f.putArray("assigneeIds").add(userId);String restricted=create(f);agenda.releaseDue(at(start));String rt=tasks(restricted,king).get(0).path("id").asText();
        assertThat(tasks(restricted,other)).isEmpty();accept(rt,other,403);accept(rt,admin,403);accept(rt,king,403);
        assertThat(sql.queryForObject("SELECT count(*) FROM notifications WHERE description=? AND member_id=?",Integer.class,f.path("title").asText(),UUID.fromString(otherId))).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM notifications WHERE description=? AND member_id=?",Integer.class,f.path("title").asText(),UUID.fromString(userId))).isEqualTo(1);
    }
    @Test void concurrentAcceptancesCannotExceedCapacityOrDuplicateWorkers()throws Exception{
        for(int limit:List.of(1,2)){
            sql.update("UPDATE task_schedules SET active=false");String id=create(backlog("scheduled",limit));agenda.releaseDue(at(start));String tid=tasks(id,king).get(0).path("id").asText();
            var pool=Executors.newFixedThreadPool(4);
            try{var requests=new ArrayList<Future<Integer>>();for(Cookie c:List.of(user,other,admin,user))requests.add(pool.submit(()->mvc.perform(post("/api/tasks/"+tid+"/accept").contentType("application/json").cookie(c)).andReturn().getResponse().getStatus()));for(var req:requests)assertThat(req.get()).isIn(200,409);}
            finally{pool.shutdownNow();}
            var t=tasks(id,king).get(0);assertThat(t.path("assigneeIds")).hasSize(limit);assertThat(new HashSet<>(new Input(t).ids("assigneeIds"))).hasSize(limit);assertThat(t.path("status").asText()).isEqualTo("a_fazer");
        }
    }
    @Test void changingScheduleToBacklogOnlyChangesFutureOpeningsAndDoesNotRecreateDeletedRuns()throws Exception{
        var f=form("scheduled");String id=create(f);agenda.releaseDue(at(start));var original=tasks(id,king).get(0);long ticket=original.path("ticketNumber").asLong();
        f.put("initialStatus","backlog").put("maxAssignees",1);call(put("/api/agenda/"+id),admin,f,200);
        assertThat(agenda.releaseDue(at(start))).isZero();assertThat(tasks(id,king).get(0).path("ticketNumber").asLong()).isEqualTo(ticket);assertThat(tasks(id,king).get(0).path("assigneeIds")).hasSize(2);
        assertThat(agenda.releaseDue(at(start.plusDays(3)))).isEqualTo(1);
        var next=tasks(id,king).stream().filter(t->t.path("status").asText().equals("backlog")).findFirst().orElseThrow();assertThat(next.path("assigneeIds")).isEmpty();
        call(delete("/api/tasks/"+next.path("id").asText()),admin,null,204);sql.update("UPDATE task_schedules SET next_run_at=? WHERE id=?",Timestamp.from(at(start.plusDays(3))),UUID.fromString(id));assertThat(agenda.releaseDue(at(start.plusDays(3)))).isZero();
    }
    @Test void validatesInitialStatusAndQuotaWithoutLimitingCandidateCount()throws Exception{
        var f=backlog("scheduled",0);call(post("/api/agenda"),admin,f,400);f.put("maxAssignees",101);call(post("/api/agenda"),admin,f,400);f.put("maxAssignees",1).put("initialStatus","concluido");call(post("/api/agenda"),admin,f,400);
        f.put("initialStatus","backlog");call(post("/api/agenda"),admin,f,201);f.put("initialStatus","a_fazer").putArray("assigneeIds");call(post("/api/agenda"),admin,f,400);
        f.put("initialStatus","backlog");call(post("/api/agenda"),admin,f,201);
    }
    @Test void v11PreservesLegacyIndividualTasksAndFutureRulesBecomeShared()throws Exception{
        try(var pg=EmbeddedPostgres.builder().setServerConfig("unix_socket_directories","").setPort(0).start()){
            var ds=pg.getPostgresDatabase();var local=new JdbcTemplate(ds);org.flywaydb.core.Flyway.configure().dataSource(ds).target("10").load().migrate();
            UUID person=UUID.randomUUID(),schedule=UUID.randomUUID(),task=UUID.randomUUID();local.update("INSERT INTO members(id,name,email,role) VALUES(?,'Antigo','legacy1112@example.test','usuario')",person);
            var config=form("recurring");config.putArray("assigneeIds").add(person.toString());local.update("INSERT INTO task_schedules(id,title,config,created_by,next_run_at) VALUES(?,'Antiga',?::jsonb,?,?)",schedule,config.toString(),person,Timestamp.from(at(start)));
            local.update("INSERT INTO tasks(id,title,description,category,status,priority,assignee_ids,open,max_assignees,checklist,created_by,created_at,updated_at,position,schedule_id,scheduled_for,schedule_member_id) VALUES(?,'Cópia antiga','','gestao','a_fazer','media',?::uuid[],false,1,'[]',?,?,?,1,?,?,?)",task,Store.array(List.of(person)),person,start,start,schedule,Timestamp.from(at(start)),person);
            local.update("INSERT INTO task_schedule_runs(id,schedule_id,scheduled_for,member_id,task_id) VALUES(gen_random_uuid(),?,?,?,?)",schedule,Timestamp.from(at(start)),person,task);
            long ticket=local.queryForObject("SELECT ticket_number FROM tasks WHERE id=?",Long.class,task);
            org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();
            assertThat(local.queryForObject("SELECT ticket_number FROM tasks WHERE id=?",Long.class,task)).isEqualTo(ticket);assertThat(local.queryForObject("SELECT schedule_member_id FROM tasks WHERE id=?",UUID.class,task)).isEqualTo(person);
            assertThat(local.queryForObject("SELECT config->>'initialStatus' FROM task_schedules WHERE id=?",String.class,schedule)).isEqualTo("a_fazer");
            AgendaService worker=new AgendaService(new Store(local,json),json);
            // Old occurrences (including deleted tasks) must not be generated again.
            assertThat(worker.releaseDue(at(start))).isZero();local.update("UPDATE task_schedules SET next_run_at=? WHERE id=?",Timestamp.from(at(start.plusDays(1))),schedule);assertThat(worker.releaseDue(at(start.plusDays(1)))).isEqualTo(1);
            assertThat(local.queryForObject("SELECT count(*) FROM tasks WHERE schedule_id=? AND schedule_member_id IS NULL",Integer.class,schedule)).isEqualTo(1);
        }
    }

}
