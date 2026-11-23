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

@SpringBootTest(properties={"SESSION_SECURE=false","ADMIN_EMAIL=driver1115@example.test","ADMIN_PASSWORD=driver1115-test-password","AGENDA_INITIAL_DELAY_MS=3600000","NOTIFICATION_INITIAL_DELAY_MS=3600000","FINANCE_INITIAL_DELAY_MS=3600000"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Driver1115IntegrationTest {
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
    String member(String name,String role)throws Exception {return call(post("/api/users"),root,Map.of("name",name,"email",name+"@example.test","role",role,"title","","senha","member1115-test-password"),201).path("id").asText();}
    @BeforeAll void accounts()throws Exception {
        root=login("driver1115@example.test","driver1115-test-password");rootId=call(get("/api/auth/me"),root,null,200).path("id").asText();
        adminId=member("administrator1115","admin"); userId=member("assignee1115","usuario"); otherId=member("other1115","usuario");
        admin=login("administrator1115@example.test","member1115-test-password"); user=login("assignee1115@example.test","member1115-test-password"); other=login("other1115@example.test","member1115-test-password");
    }


    static final String PNG="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=";
    @BeforeEach void clearFields(){sql.update("DELETE FROM driver_fields");}
    JsonNode field(String name)throws Exception{return call(post("/api/driver/fields"),admin,Map.of("name",name,"description","Descrição do campo\ncom duas linhas.","image",PNG),201);}
    JsonNode link(String field,String name)throws Exception{return call(post("/api/driver/fields/"+field+"/shortcuts"),admin,Map.of("name",name,"url","https://example.test/"+UUID.randomUUID(),"image",PNG),201);}
    JsonNode selected(String id)throws Exception{for(JsonNode f:call(get("/api/driver"),root,null,200))if(f.path("id").asText().equals(id))return f;throw new AssertionError("Missing field");}
    @Test void fieldsPersistDescriptionPhotoAndFiveIndependentShortcuts()throws Exception{
        var f=field("Aplicativo Firewalldo");String id=f.path("id").asText();for(String name:List.of("Animações","Códigos ZIP","Documentação","Design","Planejamento"))link(id,name);
        var saved=selected(id);assertThat(saved.path("description").asText()).contains("duas linhas");assertThat(saved.path("shortcuts")).hasSize(5);assertThat(saved.path("image").asText()).startsWith("/api/driver/fields/");assertThat(saved.path("createdBy").asText()).isEqualTo(adminId);
        var photo=mvc.perform(get(saved.path("image").asText()).cookie(user)).andReturn().getResponse();assertThat(photo.getStatus()).isEqualTo(200);assertThat(photo.getContentType()).isEqualTo("image/png");assertThat(photo.getContentAsByteArray()).isEqualTo(java.util.Base64.getDecoder().decode(PNG.split(",")[1]));
        var child=saved.path("shortcuts").get(0);assertThat(child.path("fieldId").asText()).isEqualTo(id);assertThat(child.path("url").asText()).startsWith("https://example.test/");assertThat(mvc.perform(get(child.path("image").asText()).cookie(user)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
    @Test void fieldAndShortcutEditingPreservesIdsAndPhotosUntilExplicitRemoval()throws Exception{
        String id=field("Zinid").path("id").asText();var shortcut=link(id,"Documentação");String sid=shortcut.path("id").asText();
        var renamed=call(patch("/api/driver/fields/"+id),admin,Map.of("name","Zinid Tecnologia","description","Texto editado"),200);assertThat(renamed.path("id").asText()).isEqualTo(id);assertThat(renamed.path("name").asText()).isEqualTo("Zinid Tecnologia");assertThat(renamed.path("shortcuts")).hasSize(1);assertThat(renamed.path("image").isNull()).isFalse();
        var updated=call(patch("/api/driver/shortcuts/"+sid),admin,Map.of("name","Manuais","url","https://example.test/manuais"),200);assertThat(updated.path("id").asText()).isEqualTo(sid);assertThat(updated.path("fieldId").asText()).isEqualTo(id);assertThat(updated.path("image").isNull()).isFalse();
        assertThat(call(patch("/api/driver/fields/"+id),root,Map.of("image",""),200).path("image").isNull()).isTrue();call(get("/api/driver/fields/"+id+"/image"),root,null,404);
        assertThat(call(patch("/api/driver/shortcuts/"+sid),root,Map.of("image",""),200).path("image").isNull()).isTrue();call(get("/api/driver/shortcuts/"+sid+"/image"),root,null,404);
    }
    @Test void ordinaryUsersReadButOnlyAdminsCanWriteAndAnonymousCannotRead()throws Exception{
        String id=field("Firewalldo").path("id").asText(),sid=link(id,"Animações").path("id").asText();assertThat(call(get("/api/driver"),user,null,200)).hasSize(1);call(get("/api/driver"),null,null,401);
        call(post("/api/driver/fields"),user,Map.of("name","Não permitido"),403);call(patch("/api/driver/fields/"+id),user,Map.of("name","Não permitido"),403);call(delete("/api/driver/fields/"+id),user,null,403);
        call(post("/api/driver/fields/"+id+"/shortcuts"),user,Map.of("name","Novo","url","https://example.test"),403);call(patch("/api/driver/shortcuts/"+sid),user,Map.of("name","Não permitido"),403);call(delete("/api/driver/shortcuts/"+sid),user,null,403);call(get("/api/driver/fields/"+id+"/image"),null,null,401);
    }
    @Test void deletionRemovesChildrenAndTheirImagesButKeepsAuditWithActualAuthor()throws Exception{
        String id=field("Campo removível").path("id").asText(),sid=link(id,"Atalho removível").path("id").asText();call(delete("/api/driver/fields/"+id),admin,null,204);assertThat(call(get("/api/driver"),root,null,200)).isEmpty();call(get("/api/driver/shortcuts/"+sid+"/image"),root,null,404);
        assertThat(sql.queryForObject("SELECT count(*) FROM driver_shortcuts WHERE field_id=?",Integer.class,UUID.fromString(id))).isZero();
        var audit=call(get("/api/reports").param("entity","driver_shortcuts").param("q",sid).param("action","exclusao"),root,null,200);assertThat(audit.path("total").asInt()).isEqualTo(1);var row=audit.path("items").get(0);assertThat(row.path("actorId").asText()).isEqualTo(adminId);assertThat(row.path("targetName").asText()).isEqualTo("Atalho removível");
        assertThat(call(get("/api/audit").param("entity","driver_fields").param("q",id).param("action","exclusao"),root,null,200).path("total").asInt()).isEqualTo(1);
    }
    @Test void updatesAreAtomicAndAuditNeverContainsImageBytes()throws Exception{
        String id=field("Original").path("id").asText();var before=selected(id);String bad="data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString("not-a-png".getBytes());
        call(patch("/api/driver/fields/"+id),admin,Map.of("name","Não deve salvar","image",bad),400);assertThat(selected(id).path("name")).isEqualTo(before.path("name"));
        call(patch("/api/driver/fields/"+id),admin,Map.of("name","Renomeado","image",PNG),200);
        var events=call(get("/api/reports").param("entity","driver_fields").param("q",id),root,null,200);assertThat(events.path("total").asInt()).isEqualTo(2);assertThat(events.toString()).doesNotContain("image_content",PNG.split(",")[1]);assertThat(events.path("items").get(0).path("details").path("changes").path("name").path("after").asText()).isEqualTo("Renomeado");
    }
    @Test void rejectsUnsafeUrlsInvalidFieldsAndMissingParents()throws Exception{
        String id=field("Links validados").path("id").asText();for(String url:List.of("javascript:alert(1)","data:text/html,hello","file:///etc/passwd","ftp://example.test/a","https://user:pass@example.test/a",""))call(post("/api/driver/fields/"+id+"/shortcuts"),admin,Map.of("name","Link inválido","url",url),400);
        call(post("/api/driver/fields"),admin,Map.of("name","x"),400);call(post("/api/driver/fields"),admin,Map.of("name","Válido","createdBy",userId),400);call(post("/api/driver/fields"),admin,Map.of("name","Válido","description","x".repeat(4001)),400);
        call(post("/api/driver/fields/"+UUID.randomUUID()+"/shortcuts"),admin,Map.of("name","Válido","url","https://example.test"),404);call(patch("/api/driver/shortcuts/"+UUID.randomUUID()),admin,Map.of("name","Novo"),404);call(delete("/api/driver/fields/not-an-id"),root,null,400);
    }
    @Test void driverDoesNotModifyExistingDashboardShortcuts()throws Exception{
        var before=call(get("/api/settings/shortcuts"),root,null,200);String id=field("Catálogo separado").path("id").asText();String sid=link(id,"Somente Driver").path("id").asText();call(delete("/api/driver/shortcuts/"+sid),admin,null,204);call(delete("/api/driver/fields/"+id),admin,null,204);assertThat(call(get("/api/settings/shortcuts"),root,null,200)).isEqualTo(before);
    }
    @Test void migrationFromV12PreservesExistingProjectsHistoryAndDashboardLinks()throws Exception{
        try(var pg=EmbeddedPostgres.builder().setServerConfig("unix_socket_directories","").setPort(0).start()){
            var ds=pg.getPostgresDatabase();var local=new JdbcTemplate(ds);Flyway.configure().dataSource(ds).target("12").load().migrate();UUID pid=UUID.randomUUID();local.update("INSERT INTO projects(id,name,subtitle,description,icon,color,owner_ids,start_date,due_date,milestones,status) VALUES(?,'Projeto preservado','','Descrição completa','code','#2563eb','{}',current_date,current_date,'[]','planejamento')",pid);local.update("INSERT INTO shortcuts(id,label,url,icon,position) VALUES('legacy-driver','Anterior','https://example.test','link',0)");String audit=local.queryForObject("SELECT details::text FROM audit_logs WHERE entity_id=?",String.class,pid.toString());
            Flyway.configure().dataSource(ds).load().migrate();assertThat(local.queryForObject("SELECT description FROM projects WHERE id=?",String.class,pid)).isEqualTo("Descrição completa");assertThat(local.queryForObject("SELECT label FROM shortcuts WHERE id='legacy-driver'",String.class)).isEqualTo("Anterior");assertThat(local.queryForObject("SELECT details::text FROM audit_logs WHERE entity_id=?",String.class,pid.toString())).isEqualTo(audit);assertThat(local.queryForObject("SELECT count(*) FROM driver_fields",Integer.class)).isZero();
        }
    }

}
