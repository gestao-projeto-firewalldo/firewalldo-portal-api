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

    @Test void releasesOnlyAtOpeningAndEachPersonCompletesIndependently()throws Exception{
        String id=create(form("scheduled"));assertThat(agenda.releaseDue(at(start).minusSeconds(1))).isZero();assertThat(tasks(id,king)).isEmpty();
        assertThat(agenda.releaseDue(at(start))).isEqualTo(2);var all=tasks(id,king);assertThat(all).hasSize(2);assertThat(all.get(0).path("id")).isNotEqualTo(all.get(1).path("id"));
        var mine=tasks(id,user).get(0);var theirs=tasks(id,other).get(0);assertThat(mine.path("assigneeIds").size()).isEqualTo(1);assertThat(mine.path("dueDate").asText()).isEqualTo(start.plusDays(2).toString());
        call(patch("/api/tasks/"+mine.path("id").asText()),user,Map.of("status","concluido","checklist",List.of(Map.of("id","check-1","text","Verificar alertas","done",true))),200);
        assertThat(tasks(id,other).get(0).path("status").asText()).isEqualTo("a_fazer");assertThat(tasks(id,other).get(0).path("checklist").get(0).path("done").asBoolean()).isFalse();
        call(patch("/api/tasks/"+theirs.path("id").asText()),user,Map.of("status","concluido"),403);
        call(get("/api/tasks").param("assigneeId",otherId),user,null,403);
        call(get("/api/tasks").param("assigneeId",otherId),admin,null,200);
        call(patch("/api/tasks/"+mine.path("id").asText()),admin,Map.of("title","Editar apenas esta ocorrência"),200);
        call(patch("/api/tasks/"+mine.path("id").asText()),admin,Map.of("assigneeIds",List.of(otherId)),400);
    }
    @Test void retryConcurrentWorkersAndDeletedTaskDoNotDuplicate()throws Exception{
        String id=create(form("scheduled"));var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->agenda.releaseDue(at(start)));var b=pool.submit(()->agenda.releaseDue(at(start)));assertThat(a.get()+b.get()).isEqualTo(2);}finally{pool.shutdownNow();}
        String task=tasks(id,king).get(0).path("id").asText();call(delete("/api/tasks/"+task),admin,null,204);
        sql.update("UPDATE task_schedules SET next_run_at=? WHERE id=?",Timestamp.from(at(start)),UUID.fromString(id));
        assertThat(agenda.releaseDue(at(start))).isZero();assertThat(tasks(id,king)).hasSize(1);
        assertThat(agenda.releaseDue(at(start.plusDays(3)))).isEqualTo(2);
    }
    @Test void dailyIntervalWeeklyAndScheduledRulesUseBrasiliaAndCatchUp()throws Exception{
        ObjectNode daily=form("recurring");daily.put("intervalDays",2);String id=create(daily);
        assertThat(agenda.releaseDue(at(start.plusDays(5)))).isEqualTo(6);assertThat(tasks(id,king)).hasSize(6);
        assertThat(sql.queryForObject("SELECT next_run_at FROM task_schedules WHERE id=?",Timestamp.class,UUID.fromString(id)).toInstant()).isEqualTo(at(start.plusDays(6)));
        sql.update("UPDATE task_schedules SET active=false");String weekly=create(form("weekly"));
        assertThat(agenda.releaseDue(at(start.plusDays(6)))).isEqualTo(2);assertThat(tasks(weekly,king)).hasSize(2);
        assertThat(agenda.releaseDue(at(start.plusDays(7)))).isEqualTo(2);
        assertThat(at(start).atOffset(ZoneOffset.UTC).getHour()).isEqualTo(12);
    }
    @Test void editPauseResumeAndDeletePreserveReleasedTasks()throws Exception{
        ObjectNode f=form("scheduled");String id=create(f);agenda.releaseDue(at(start));
        f.put("title","Nova descrição da regra");f.putArray("assigneeIds").add(userId);call(put("/api/agenda/"+id),king,f,200);
        agenda.releaseDue(at(start));assertThat(tasks(id,king)).hasSize(2);
        call(patch("/api/agenda/"+id+"/active"),admin,Map.of("active",false),200);assertThat(agenda.releaseDue(at(start.plusDays(3)))).isZero();
        call(patch("/api/agenda/"+id+"/active"),king,Map.of("active",true),200);
        assertThat(agenda.releaseDue(at(start.plusDays(3)))).isEqualTo(1);assertThat(tasks(id,king)).hasSize(3);
        call(delete("/api/agenda/"+id),admin,null,204);assertThat(tasks(id,king)).hasSize(3);
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
        assertThat(selected.path("assigneeIds").size()).isEqualTo(1);assertThat(selected.path("upcoming").size()).isEqualTo(6);
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
        assertThat(agenda.releaseDue(at(start.plusDays(20)))).isEqualTo(4);assertThat(tasks(id,king)).hasSize(4);assertThat(sql.queryForObject("SELECT next_run_at IS NULL FROM task_schedules WHERE id=?",Boolean.class,UUID.fromString(id))).isTrue();
        f=form("recurring");String back=create(f);assertThat(agenda.releaseDue(at(start.plusDays(30)))).isEqualTo(40);assertThat(tasks(back,king)).hasSize(40);
        assertThat(agenda.releaseDue(at(start.plusDays(30)))).isEqualTo(22);assertThat(tasks(back,king)).hasSize(62);
    }
    @Test void globalTicketsFollowLaunchOrderAcrossManualAndAgendaAndDoNotChangeOnEdit()throws Exception{
        long before=sql.queryForObject("SELECT last_number FROM task_ticket_counter",Long.class);
        var manual=json.createObjectNode().put("title","Primeiro ticket manual").put("description","").putNull("projectId").put("category","gestao").put("status","a_fazer").put("priority","media").put("open",false).put("maxAssignees",1).putNull("dueDate");manual.putArray("assigneeIds").add(userId);manual.putArray("checklist");
        var first=call(post("/api/tasks"),admin,manual,201);assertThat(first.path("ticketNumber").asLong()).isEqualTo(before+1);
        call(patch("/api/tasks/"+first.path("id").asText()),admin,Map.of("title","Ticket editado"),200);
        var unchanged=call(patch("/api/tasks/"+first.path("id").asText()),user,Map.of("status","concluido"),200);assertThat(unchanged.path("ticketNumber").asLong()).isEqualTo(before+1);
        call(patch("/api/tasks/"+first.path("id").asText()),admin,Map.of("ticketNumber",9000),400);
        manual.put("title","x");call(post("/api/tasks"),admin,manual,400);assertThat(sql.queryForObject("SELECT last_number FROM task_ticket_counter",Long.class)).isEqualTo(before+1);
        String id=create(form("scheduled"));assertThat(sql.queryForObject("SELECT last_number FROM task_ticket_counter",Long.class)).isEqualTo(before+1);
        agenda.releaseDue(at(start));assertThat(tasks(id,king).stream().map(t->t.path("ticketNumber").asLong()).sorted().toList()).containsExactly(before+2,before+3);
        agenda.releaseDue(at(start.plusDays(3)));assertThat(tasks(id,king).stream().map(t->t.path("ticketNumber").asLong()).sorted().toList()).containsExactly(before+2,before+3,before+4,before+5);
        call(delete("/api/tasks/"+first.path("id").asText()),admin,null,204);manual.put("title","Último ticket manual");assertThat(call(post("/api/tasks"),admin,manual,201).path("ticketNumber").asLong()).isEqualTo(before+6);
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

}
