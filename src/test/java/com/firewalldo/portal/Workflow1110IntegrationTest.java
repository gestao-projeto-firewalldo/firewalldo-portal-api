package com.firewalldo.portal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
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

@SpringBootTest(properties={"SESSION_SECURE=false","ADMIN_EMAIL=workflow1110@example.test","ADMIN_PASSWORD=workflow1110-test-password","AGENDA_INITIAL_DELAY_MS=3600000","NOTIFICATION_INITIAL_DELAY_MS=3600000","FINANCE_INITIAL_DELAY_MS=3600000"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Workflow1110IntegrationTest {
    static final EmbeddedPostgres PG;
    static final UUID LEGACY=UUID.randomUUID();
    static String legacyAudit;
    static {
        try {
            PG=EmbeddedPostgres.builder().setServerConfig("unix_socket_directories","").setPort(0).start();
            Flyway.configure().dataSource(PG.getPostgresDatabase()).locations("classpath:db/migration").target("8").load().migrate();
            var sql=new JdbcTemplate(PG.getPostgresDatabase());
            sql.update("INSERT INTO projects(id,name,subtitle,description,icon,color,owner_ids,start_date,due_date,milestones,status,brand) VALUES(?,'Projeto legado','','','code','#2563eb','{}',current_date,current_date,'[]','planejamento','')",LEGACY);
            legacyAudit=sql.queryForObject("SELECT details::text FROM audit_logs WHERE entity_id=?",String.class,LEGACY.toString());
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate sql; @Autowired AgendaService agenda;
    Cookie root,admin,user,other; String rootId,adminId,userId,otherId;
    JsonNode call(MockHttpServletRequestBuilder req,Cookie cookie,Object body,int expected)throws Exception {
        req.contentType("application/json").with(r->{r.setRemoteAddr("203.0.113.42");return r;});
        if(cookie!=null)req.cookie(cookie); if(body!=null)req.content(json.writeValueAsBytes(body));
        var result=mvc.perform(req).andReturn().getResponse();
        assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(expected);
        return result.getContentAsByteArray().length==0?json.nullNode():json.readTree(result.getContentAsByteArray());
    }
    Cookie login(String email,String password)throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsBytes(Map.of("email",email,"senha",password)))).andReturn().getResponse().getCookie("fw_session");
    }
    String member(String name,String role)throws Exception {return call(post("/api/users"),root,Map.of("name",name,"email",name+"@example.test","role",role,"title","","senha","member1110-test-password"),201).path("id").asText();}
    @BeforeAll void accounts()throws Exception {
        root=login("workflow1110@example.test","workflow1110-test-password");rootId=call(get("/api/auth/me"),root,null,200).path("id").asText();
        adminId=member("administrator1110","admin"); userId=member("assignee1110","usuario"); otherId=member("other1110","usuario");
        admin=login("administrator1110@example.test","member1110-test-password"); user=login("assignee1110@example.test","member1110-test-password"); other=login("other1110@example.test","member1110-test-password");
    }
    ObjectNode taskForm(String owner,String status) {
        var f=json.createObjectNode().put("title","Task "+UUID.randomUUID()).put("description","").putNull("projectId").put("category","design").put("status",status).put("priority","media").put("open",owner==null).put("maxAssignees",1).putNull("dueDate");
        f.putArray("checklist");var people=f.putArray("assigneeIds");if(owner!=null)people.add(owner);return f;
    }
    String task(String owner)throws Exception{return call(post("/api/tasks"),root,taskForm(owner,"a_fazer"),201).path("id").asText();}
    String project()throws Exception{return call(post("/api/projects"),admin,Map.of("name","Projeto removível "+UUID.randomUUID(),"subtitle","","description","","icon","code","color","#2563eb","ownerIds",List.of(adminId),"dueDate",Input.today().plusDays(10).toString()),201).path("id").asText();}

    @Test void upgradePreservesExistingProjectAndImmutableHistory()throws Exception {
        assertThat(call(get("/api/projects/"+LEGACY),root,null,200).path("name").asText()).isEqualTo("Projeto legado");
        assertThat(sql.queryForObject("SELECT details::text FROM audit_logs WHERE entity_id=?",String.class,LEGACY.toString())).isEqualTo(legacyAudit);
        assertThatThrownBy(()->sql.update("DELETE FROM audit_logs WHERE entity_id=?",LEGACY.toString())).hasMessageContaining("somente leitura");
    }
    @Test void ownerCanAdvanceAndReturnOnlyThroughSpecifiedStages()throws Exception {
        String id=task(userId);
        call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","backlog"),400);
        call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","concluido"),400);
        call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","em_andamento"),204);
        call(patch("/api/tasks/"+id),user,Map.of("status","a_fazer"),200);
        call(patch("/api/tasks/"+id),user,Map.of("status","em_andamento"),200);
        call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","concluido"),204);
        call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","a_fazer"),400);
        call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","em_andamento"),204);
    }
    @Test void unlinkedAdminCannotMoveEvenByEditingStatusOrAssigningSelfInSameRequest()throws Exception {
        String id=task(userId);
        call(patch("/api/tasks/"+id+"/move"),admin,Map.of("status","em_andamento"),403);
        call(patch("/api/tasks/"+id),admin,Map.of("status","em_andamento"),403);
        call(patch("/api/tasks/"+id),admin,Map.of("status","em_andamento","assigneeIds",List.of(adminId)),403);
        call(patch("/api/tasks/"+id+"/move"),other,Map.of("status","em_andamento"),403);
        var changed=call(patch("/api/tasks/"+id),admin,Map.of("title","Metadata can still be managed"),200);
        assertThat(changed.path("status").asText()).isEqualTo("a_fazer");
        assertThat(changed.path("assigneeIds").get(0).asText()).isEqualTo(userId);
        call(patch("/api/tasks/"+id+"/move"),root,Map.of("status","em_andamento"),204);
        call(patch("/api/tasks/"+id+"/move"),root,Map.of("status","concluido"),204);
        call(patch("/api/tasks/"+id+"/move"),admin,Map.of("status","em_andamento"),403);
        call(patch("/api/tasks/"+id+"/move"),root,Map.of("status","em_andamento"),204);
        call(patch("/api/tasks/"+task(adminId)+"/move"),admin,Map.of("status","em_andamento"),204);
    }
    @Test void backlogIsVisibleAndAcceptedWithoutManualMovingOrStageSkipping()throws Exception {
        String id=call(post("/api/tasks"),root,taskForm(null,"backlog"),201).path("id").asText();
        assertThat(call(get("/api/tasks"),user,null,200).findValuesAsText("id")).contains(id);
        call(patch("/api/tasks/"+id+"/move"),root,Map.of("status","a_fazer"),400);
        call(patch("/api/tasks/"+id),root,Map.of("status","a_fazer"),400);
        call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","a_fazer"),403);
        assertThat(call(post("/api/tasks/"+id+"/accept"),user,Map.of(),200).path("status").asText()).isEqualTo("a_fazer");
        call(post("/api/tasks/"+id+"/accept"),other,Map.of(),409);
        assertThat(call(get("/api/tasks"),other,null,200).findValuesAsText("id")).doesNotContain(id);
        call(post("/api/tasks"),root,taskForm(userId,"concluido"),400);
        call(post("/api/tasks"),admin,taskForm(adminId,"em_andamento"),400);
    }
    @Test void auditDetailsCaptureActualAuthorAndBeforeAfterWithoutSecrets()throws Exception {
        String id=task(userId);
        call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","em_andamento"),204);
        var entry=call(get("/api/audit").param("entity","tasks").param("action","edicao").param("q",id),admin,null,200).path("items").get(0);
        assertThat(entry.path("actorId").asText()).isEqualTo(userId);
        assertThat(entry.path("actorName").asText()).isEqualTo("assignee1110");
        assertThat(entry.path("ip").asText()).isEqualTo("203.0.113.42");
        var details=entry.path("details");
        assertThat(details.path("changes").path("status").path("before").asText()).isEqualTo("a_fazer");
        assertThat(details.path("changes").path("status").path("after").asText()).isEqualTo("em_andamento");
        assertThat(details.path("actorRole").asText()).isEqualTo("usuario");
        assertThat(details.path("route").asText()).isEqualTo("/api/tasks/{id}/move");
        String passwordAccount=member("password1110"+UUID.randomUUID().toString().replace("-",""),"usuario");
        call(patch("/api/users/"+passwordAccount),root,Map.of("senha","replacement1110-password","confirmacaoSenha","replacement1110-password"),200);
        var all=call(get("/api/audit"),root,null,200).toString();
        assertThat(all).doesNotContain("replacement1110-password","password_hash","token_hash");
        call(get("/api/audit"),user,null,403);
    }
    @Test void adminCanDeleteProjectKeepingTicketsAndFutureScheduleReleases()throws Exception {
        String id=project();String task=task(userId);
        var linked=call(patch("/api/tasks/"+task),admin,Map.of("projectId",id),200);
        long ticket=linked.path("ticketNumber").asLong();
        LocalDate day=Input.today().plusDays(2);
        var rule=json.createObjectNode().put("title","Agenda preservada").put("description","").put("projectId",id).put("category","design").put("priority","media").put("kind","scheduled").put("intervalDays",1).put("startDate",day.toString()).putNull("endDate").put("openingTime","09:00").put("deadlineDays",0);
        rule.putArray("assigneeIds").add(userId);rule.putArray("weekdays");rule.putArray("dates").add(day.toString()).add(day.plusDays(1).toString());rule.putArray("checklist");
        String schedule=call(post("/api/agenda"),admin,rule,201).path("id").asText();
        agenda.releaseDue(day.atTime(9,0).atZone(AgendaService.ZONE).toInstant());
        call(delete("/api/projects/"+id),user,null,403);
        call(get("/api/projects/"+id),root,null,200);
        call(delete("/api/projects/"+id),admin,null,204);
        call(get("/api/projects/"+id),root,null,404);
        call(delete("/api/projects/"+id),admin,null,404);
        assertThat(sql.queryForObject("SELECT ticket_number FROM tasks WHERE id=?",Long.class,UUID.fromString(task))).isEqualTo(ticket);
        assertThat(sql.queryForObject("SELECT project_id FROM tasks WHERE id=?",UUID.class,UUID.fromString(task))).isNull();
        assertThat(sql.queryForObject("SELECT config->>'projectId' FROM task_schedules WHERE id=?",String.class,UUID.fromString(schedule))).isNull();
        assertThat(agenda.releaseDue(day.plusDays(1).atTime(9,0).atZone(AgendaService.ZONE).toInstant())).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM tasks WHERE schedule_id=? AND project_id IS NULL",Integer.class,UUID.fromString(schedule))).isEqualTo(2);
        var deleted=call(get("/api/audit").param("entity","projects").param("action","exclusao").param("q",id),root,null,200).path("items").get(0);
        assertThat(deleted.path("actorId").asText()).isEqualTo(adminId);
        assertThat(deleted.path("details").path("changes").path("name").path("before").asText()).startsWith("Projeto removível");
        assertThat(deleted.path("details").path("changes").path("name").path("after").isNull()).isTrue();
        call(delete("/api/projects/"+project()),root,null,204);
    }
}
