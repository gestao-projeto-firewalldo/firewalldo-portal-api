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

@SpringBootTest(properties={"SESSION_SECURE=false","ADMIN_EMAIL=reports1113@example.test","ADMIN_PASSWORD=reports1113-test-password","AGENDA_INITIAL_DELAY_MS=3600000","NOTIFICATION_INITIAL_DELAY_MS=3600000","FINANCE_INITIAL_DELAY_MS=3600000"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Reports1113IntegrationTest {
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
    String member(String name,String role)throws Exception {return call(post("/api/users"),root,Map.of("name",name,"email",name+"@example.test","role",role,"title","","senha","member1113-test-password"),201).path("id").asText();}
    @BeforeAll void accounts()throws Exception {
        root=login("reports1113@example.test","reports1113-test-password");rootId=call(get("/api/auth/me"),root,null,200).path("id").asText();
        adminId=member("administrator1113","admin"); userId=member("assignee1113","usuario"); otherId=member("other1113","usuario");
        admin=login("administrator1113@example.test","member1113-test-password"); user=login("assignee1113@example.test","member1113-test-password"); other=login("other1113@example.test","member1113-test-password");
    }

    ObjectNode taskForm(String owner){var f=json.createObjectNode().put("title","Histórico "+UUID.randomUUID()).put("description","Descrição que deve permanecer no histórico.").putNull("projectId").put("category","design").put("status","a_fazer").put("priority","media").put("open",false).put("maxAssignees",1).putNull("dueDate");f.putArray("assigneeIds").add(owner);f.putArray("eligibleAssigneeIds");f.putArray("checklist").addObject().put("id","check-1").put("text","Conferir entrega").put("done",true);return f;}
    String task()throws Exception{return call(post("/api/tasks"),root,taskForm(userId),201).path("id").asText();}
    String project()throws Exception{return call(post("/api/projects"),admin,Map.of("name","Projeto histórico "+UUID.randomUUID(),"subtitle","","description","","icon","code","color","#2563eb","ownerIds",List.of(adminId),"dueDate",Input.today().plusDays(10).toString()),201).path("id").asText();}
    void complete(String id)throws Exception{call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","em_andamento"),204);call(patch("/api/tasks/"+id+"/move"),user,Map.of("status","concluido"),204);}
    List<JsonNode> list(Cookie actor,boolean include)throws Exception{var rows=new ArrayList<JsonNode>();call(get("/api/tasks").param("includeFinalized",Boolean.toString(include)),actor,null,200).forEach(rows::add);return rows;}
    JsonNode report(String id,String action)throws Exception{return call(get("/api/reports").param("q",id).param("action",action),root,null,200);}

    @Test void onlyPrincipalCanFinalizeCompletedTasksAndRetryIsIdempotent()throws Exception{
        String id=task();call(post("/api/tasks/"+id+"/finalize"),root,null,400);complete(id);
        call(post("/api/tasks/"+id+"/finalize"),admin,null,403);call(post("/api/tasks/"+id+"/finalize"),user,null,403);
        var saved=call(post("/api/tasks/"+id+"/finalize"),root,null,200);assertThat(saved.path("finalizedAt").isNull()).isFalse();assertThat(saved.path("finalizedBy").asText()).isEqualTo(rootId);
        assertThat(saved.path("finalizedByName").asText()).isEqualTo("Administrador");assertThat(saved.path("status").asText()).isEqualTo("concluido");
        var retry=call(post("/api/tasks/"+id+"/finalize"),root,null,200);assertThat(retry.path("finalizedAt")).isEqualTo(saved.path("finalizedAt"));assertThat(report(id,"finalizacao").path("total").asInt()).isEqualTo(1);
        var event=report(id,"finalizacao").path("items").get(0);assertThat(event.path("actorId").asText()).isEqualTo(rootId);assertThat(event.path("details").path("changes").path("finalized_at").path("after").isNull()).isFalse();
    }
    @Test void finalizedTasksLeaveBoardButStayInProjectHistoryAndStatistics()throws Exception{
        String project=project(),id=task();call(patch("/api/tasks/"+id),root,Map.of("projectId",project),200);complete(id);
        var before=list(root,true).stream().filter(t->t.path("id").asText().equals(id)).findFirst().orElseThrow();call(post("/api/tasks/"+id+"/finalize"),root,null,200);
        assertThat(list(root,false)).noneMatch(t->t.path("id").asText().equals(id));var archived=list(root,true).stream().filter(t->t.path("id").asText().equals(id)).findFirst().orElseThrow();
        assertThat(archived.path("ticketNumber")).isEqualTo(before.path("ticketNumber"));assertThat(archived.path("checklist")).isEqualTo(before.path("checklist"));assertThat(archived.path("assigneeIds")).isEqualTo(before.path("assigneeIds"));assertThat(archived.path("projectId").asText()).isEqualTo(project);
        assertThat(list(user,true)).anyMatch(t->t.path("id").asText().equals(id));assertThat(list(other,true)).noneMatch(t->t.path("id").asText().equals(id));
        assertThat(call(get("/api/projects/"+project),root,null,200).path("progress").asInt()).isEqualTo(100);
        assertThat(call(get("/api/tasks").param("projectId",project).param("includeFinalized","true"),root,null,200)).hasSize(1);
    }
    @Test void finalizedTaskCannotBeEditedMovedDeletedOrReassigned()throws Exception{
        String id=task();complete(id);call(post("/api/tasks/"+id+"/finalize"),root,null,200);
        call(patch("/api/tasks/"+id),root,Map.of("title","Tentar alterar"),409);call(patch("/api/tasks/"+id),user,Map.of("checklist",List.of()),409);
        call(patch("/api/tasks/"+id+"/move"),root,Map.of("status","em_andamento"),409);call(delete("/api/tasks/"+id),root,null,409);
        call(post("/api/tasks/"+id+"/accept"),user,null,409);call(post("/api/tasks/"+id+"/leave"),user,null,409);
    }
    @Test void deletionOfProjectKeepsFinalizedTaskAndProjectSnapshot()throws Exception{
        String pid=project(),id=task();String name=call(get("/api/projects/"+pid),root,null,200).path("name").asText();call(patch("/api/tasks/"+id),root,Map.of("projectId",pid),200);complete(id);call(post("/api/tasks/"+id+"/finalize"),root,null,200);
        call(delete("/api/projects/"+pid),admin,null,204);var saved=list(root,true).stream().filter(t->t.path("id").asText().equals(id)).findFirst().orElseThrow();assertThat(saved.path("projectId").isNull()).isTrue();assertThat(saved.path("finalizedProjectName").asText()).isEqualTo(name);assertThat(saved.path("finalizedAt").isNull()).isFalse();
        assertThat(report(pid,"exclusao").path("items").get(0).path("targetName").asText()).isEqualTo(name);
    }
    @Test void reportsShowNewsAuthorsChangesAndDeletionAfterNewsIsGone()throws Exception{
        var form=Map.of("title","Notícia rastreável "+UUID.randomUUID(),"summary","Resumo com autoria que será preservada.","body","Texto completo da notícia usado para testar o histórico de ações.","slot","geral","sourceName","Fonte oficial","sourceUrl","https://example.test/noticia");
        String id=call(post("/api/news"),root,form,201).path("id").asText();call(patch("/api/news/"+id),admin,Map.of("title","Notícia editada com autoria"),200);call(delete("/api/news/"+id),root,null,204);
        var rows=report(id,"");assertThat(rows.path("total").asInt()).isEqualTo(3);var created=report(id,"criacao").path("items").get(0);assertThat(created.path("actorId").asText()).isEqualTo(rootId);
        var edit=report(id,"edicao").path("items").get(0);assertThat(edit.path("actorId").asText()).isEqualTo(adminId);assertThat(edit.path("details").path("changes").path("title").path("after").asText()).isEqualTo("Notícia editada com autoria");
        var filtered=call(get("/api/reports").param("q",id).param("actorId",adminId).param("entity","news"),admin,null,200);assertThat(filtered.path("total").asInt()).isEqualTo(1);
        assertThat(rows.path("summary").path("deletions").asInt()).isEqualTo(1);assertThat(rows.toString()).doesNotContain("password_hash","token_hash","image_content");
    }
    @Test void completionAndLegacyCompletionRecordsAreFilterable()throws Exception{
        String id=task();complete(id);var done=report(id,"conclusao");assertThat(done.path("total").asInt()).isEqualTo(1);assertThat(done.path("items").get(0).path("actorId").asText()).isEqualTo(userId);
        String marker=UUID.randomUUID().toString();sql.update("INSERT INTO audit_logs(actor_name,action,entity,target_name,details) VALUES('Autor antigo','edicao','projects',?,?::jsonb)",marker,"{\"changes\":{\"status\":{\"before\":\"em_andamento\",\"after\":\"concluido\"}}}");
        assertThat(report(marker,"conclusao").path("total").asInt()).isEqualTo(1);
        call(get("/api/reports"),user,null,403);call(get("/api/reports/export"),user,null,403);call(get("/api/reports").param("action","invalid"),root,null,400);call(get("/api/reports").param("actorId","x"),root,null,400);
        assertThat(sql.queryForObject("SELECT details::text FROM audit_logs WHERE entity_id=?",String.class,LEGACY.toString())).isEqualTo(legacyAudit);assertThatThrownBy(()->sql.update("DELETE FROM audit_logs WHERE entity_id=?",LEGACY.toString())).hasMessageContaining("somente leitura");
    }
    @Test void paginationSummaryAndBrasiliaDateFiltersCoverAllRecords()throws Exception{
        String marker=UUID.randomUUID().toString();sql.update("INSERT INTO audit_logs(actor_name,action,entity,target_name,created_at) SELECT 'Sistema','criacao','ideas',?, TIMESTAMPTZ '2026-11-23 03:30:00+00' + n*INTERVAL '1 second' FROM generate_series(1,51) n",marker);
        var first=call(get("/api/reports").param("q",marker).param("from","2026-11-23").param("to","2026-11-23"),root,null,200);assertThat(first.path("items")).hasSize(50);assertThat(first.path("total").asInt()).isEqualTo(51);assertThat(first.path("summary").path("creations").asInt()).isEqualTo(51);
        var second=call(get("/api/reports").param("q",marker).param("page","2"),root,null,200);assertThat(second.path("items")).hasSize(1);assertThat(first.path("items").get(49).path("id")).isNotEqualTo(second.path("items").get(0).path("id"));
        assertThat(call(get("/api/reports").param("q",marker).param("to","2026-11-22"),root,null,200).path("total").asInt()).isZero();call(get("/api/reports").param("from","2026-11-24").param("to","2026-11-22"),root,null,400);
    }
    @Test void csvExportsFilteredHistoryEscapesFormulasAndRejectsSilentTruncation()throws Exception{
        String marker=UUID.randomUUID().toString();sql.update("INSERT INTO audit_logs(actor_name,action,entity,target_name) VALUES('Autor CSV','criacao','projects',?)",marker+"; =Fórmula\nTítulo");sql.update("INSERT INTO audit_logs(actor_name,action,entity,target_name) VALUES('Autor CSV','criacao','projects',?)","=SUM(1+1) "+marker);
        var response=mvc.perform(get("/api/reports/export").param("q",marker).cookie(root)).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(200);String csv=response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertThat(csv).startsWith("\uFEFFRegistro;").contains("'="+"SUM(1+1)","Autor CSV");assertThat(response.getHeader("Content-Disposition")).contains(".csv");
        String many=UUID.randomUUID().toString();sql.update("INSERT INTO audit_logs(actor_name,action,entity,target_name) SELECT 'Sistema','criacao','ideas',? FROM generate_series(1,20001)",many);call(get("/api/reports/export").param("q",many),root,null,400);
    }

}
