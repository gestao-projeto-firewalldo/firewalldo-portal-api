package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"SESSION_SECURE=false","ADMIN_EMAIL=initial@example.test","ADMIN_PASSWORD=initial-test-password","ADMIN_NAME=Initial Admin"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdministrationIntegrationTest {
    static final UUID INITIAL = UUID.randomUUID(), PENDING = UUID.randomUUID();
    static final EmbeddedPostgres PG;
    static {
        try {
            PG = EmbeddedPostgres.builder().setServerConfig("unix_socket_directories", "").setPort(0).start();
            var source = PG.getPostgresDatabase();
            // Start with the schema and real accounts from the previous release.
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("1").load().migrate();
            var sql = new JdbcTemplate(source);
            sql.update("INSERT INTO members(id,name,email,role,password_hash) VALUES(?,?,?,'admin',?)", INITIAL,"Initial Admin","initial@example.test", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("initial-test-password"));
            sql.update("INSERT INTO members(id,name,email,role) VALUES(?,?,?,'admin')",PENDING,"Pending Admin","pending@example.test");
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate sql;
    @Autowired NotificationJobs jobs;
    static Cookie root, admin, user;
    static String adminId, userId;

    MvcResult call(MockHttpServletRequestBuilder req, Cookie cookie, Object body, int expected) throws Exception {
        req.contentType("application/json").with(r -> { r.setRemoteAddr("203.0.113.9"); return r; });
        if (cookie != null) req.cookie(cookie);
        if (body != null) req.content(json.writeValueAsBytes(body));
        var result = mvc.perform(req).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
        return result;
    }
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    Cookie login(String email,String password) throws Exception {
        return call(post("/api/auth/login"),null,Map.of("email",email,"senha",password),200).getResponse().getCookie("fw_session");
    }
    String create(String name,String email,String role) throws Exception {
        return body(call(post("/api/users"),root,Map.of("name",name,"email",email,"role",role,"title","Developer","senha","account-test-password"),201)).path("id").asText();
    }

    @Test @Order(1) void migratesExistingAccountsAndPromotesOnlyInitialAdmin() throws Exception {
        assertThat(sql.queryForObject("SELECT role FROM members WHERE id=?",String.class,INITIAL)).isEqualTo("superadmin");
        assertThat(sql.queryForObject("SELECT role FROM members WHERE id=?",String.class,PENDING)).isEqualTo("admin");
        assertThat(sql.queryForObject("SELECT count(*) FROM members",Integer.class)).isEqualTo(2);
        root=login("initial@example.test","initial-test-password");
        adminId=create("Regular Admin","admin@example.test","admin");
        userId=create("Regular User","user@example.test","usuario");
        admin=login("admin@example.test","account-test-password");
        user=login("user@example.test","account-test-password");
        var list=body(call(get("/api/users"),root,null,200));
        assertThat(list.toString()).doesNotContain("password_hash","account-test-password");
        call(patch("/api/users/"+PENDING),root,Map.of("senha","pending-test-password","confirmacaoSenha","pending-test-password","email","boss@example.test"),200);
        assertThat(login("boss@example.test","pending-test-password")).isNotNull();
    }

    @Test @Order(2) void enforcesRoleHierarchyOnServer() throws Exception {
        call(patch("/api/users/"+INITIAL),admin,Map.of("name","Blocked"),403);
        call(patch("/api/users/"+PENDING),admin,Map.of("senha","blocked-test-password","confirmacaoSenha","blocked-test-password"),403);
        call(patch("/api/users/"+adminId),admin,Map.of("role","superadmin"),403);
        call(patch("/api/users/"+userId),user,Map.of("role","admin"),403);
        call(patch("/api/users/"+adminId),user,Map.of("name","Blocked"),403);
        call(post("/api/users"),admin,Map.of("name","Invalid Admin","email","invalid@example.test","title","","role","admin","senha","account-test-password"),403);
        call(patch("/api/users/"+userId),admin,Map.of("name","Edited by Admin","senha","reset-test-password","confirmacaoSenha","reset-test-password"),200);
        call(get("/api/auth/me"),user,null,401);
        user=login("user@example.test","reset-test-password");
        call(patch("/api/users/"+INITIAL),root,Map.of("active",false),409);
        call(patch("/api/users/"+INITIAL),root,Map.of("role","usuario"),409);
    }

    @Test @Order(3) void selfPasswordChangeChecksConfirmationAndRevokesOtherSessions() throws Exception {
        Cookie old=login("user@example.test","reset-test-password");
        call(patch("/api/users/"+userId),user,Map.of("senha","new-user-test-password","confirmacaoSenha","wrong-confirmation","senhaAtual","reset-test-password"),400);
        call(patch("/api/users/"+userId),user,Map.of("senha","new-user-test-password","confirmacaoSenha","new-user-test-password","senhaAtual","wrong-password"),400);
        call(patch("/api/users/"+userId),user,Map.of("senha","new-user-test-password","confirmacaoSenha","new-user-test-password","senhaAtual","reset-test-password"),200);
        call(get("/api/auth/me"),user,null,200);
        call(get("/api/auth/me"),old,null,401);
        call(post("/api/auth/login"),null,Map.of("email","user@example.test","senha","reset-test-password"),401);
        assertThat(login("user@example.test","new-user-test-password")).isNotNull();
    }

    @Test @Order(4) void auditCapturesActorIpFailedLoginsAndFiltersWithoutSecrets() throws Exception {
        call(post("/api/ideas").header("X-Portal-Client-IP","1.2.3.4"),user,Map.of("title","Audit Test Idea","description","Test description","category","guia"),201);
        call(post("/api/auth/login"),null,Map.of("email","nonexistent@example.test","senha","wrong-password"),401);
        call(get("/api/audit"),user,null,403);
        var audit=body(call(get("/api/audit").param("entity","ideas").param("q","Edited by Admin"),root,null,200));
        assertThat(audit.path("total").asInt()).isEqualTo(1);
        var entry=audit.path("items").get(0);
        assertThat(entry.path("action").asText()).isEqualTo("criacao");
        assertThat(entry.path("actorName").asText()).isEqualTo("Edited by Admin");
        assertThat(entry.path("ip").asText()).isEqualTo("203.0.113.9");
        var failures=body(call(get("/api/audit").param("action","login").param("outcome","falha").param("q","nonexistent@example.test"),admin,null,200));
        assertThat(failures.path("items").get(0).path("actorName").asText()).isEqualTo("Sistema");
        var knownFailure=body(call(get("/api/audit").param("action","login").param("outcome","falha").param("q","user@example.test"),root,null,200)).path("items").get(0);
        assertThat(knownFailure.path("actorName").asText()).isEqualTo("Sistema");
        assertThat(knownFailure.path("actorId").isNull()).isTrue();
        assertThat(knownFailure.path("targetName").asText()).contains("user@example.test");
        var all=body(call(get("/api/audit"),root,null,200));
        assertThat(all.toString()).doesNotContain("password_hash","reset-test-password","new-user-test-password","fw_session","token_hash");
        assertThat(all.path("items").findValues("passwordChanged")).contains(json.getNodeFactory().booleanNode(true));
        call(get("/api/audit").param("from","2026-11-19").param("to","2026-11-18"),root,null,400);
    }

    @Test @Order(5) void configurationDiffRecordsOnlyChangedShortcutsAndAuditIsReadOnly() throws Exception {
        var shortcuts=body(call(get("/api/settings/shortcuts"),root,null,200));
        int before=sql.queryForObject("SELECT count(*) FROM audit_logs WHERE entity='shortcuts'",Integer.class);
        call(put("/api/settings/shortcuts"),root,shortcuts,200);
        assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE entity='shortcuts'",Integer.class)).isEqualTo(before);
        ((com.fasterxml.jackson.databind.node.ObjectNode)shortcuts.get(0)).put("label","Updated shortcut");
        call(put("/api/settings/shortcuts"),root,shortcuts,200);
        assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE entity='shortcuts' AND action='edicao'",Integer.class)).isEqualTo(before+1);
        assertThatThrownBy(() -> sql.update("DELETE FROM audit_logs")).hasMessageContaining("somente leitura");
        assertThatThrownBy(() -> sql.update("TRUNCATE audit_logs")).hasMessageContaining("somente leitura");
    }

    @Test @Order(6) void rollsBackFailedMutationsAndLogsSystemChanges() throws Exception {
        int members=sql.queryForObject("SELECT count(*) FROM members",Integer.class);
        call(post("/api/users"),root,Map.of("name","Duplicate","email","user@example.test","role","usuario","title","","senha","account-test-password"),409);
        assertThat(sql.queryForObject("SELECT count(*) FROM members",Integer.class)).isEqualTo(members);
        sql.update("INSERT INTO ideas(id,author_id,title,description,category,created_at) VALUES(?,?,?,?,'guia',current_date)",UUID.randomUUID(),INITIAL,"System idea","Automatic");
        var system=body(call(get("/api/audit").param("q","System idea"),root,null,200)).path("items").get(0);
        assertThat(system.path("actorName").asText()).isEqualTo("Sistema");
        assertThat(system.path("ip").isNull()).isTrue();
        call(post("/api/auth/logout"),user,Map.of(),204);
    }
    @Test @Order(7) void capturesTaskCreationEditDeletionAndPagedAudit() throws Exception {
        var project=body(call(post("/api/projects"),root,Map.of("name","Audit Project","subtitle","","description","","icon","code","color","#2563eb","ownerIds",java.util.List.of(INITIAL.toString()),"dueDate","2026-12-30"),201));
        var task=json.createObjectNode().put("title","Audited Task").put("description","").put("projectId",project.path("id").asText())
          .put("category","programacao").put("status","backlog").put("priority","media").put("open",true).put("maxAssignees",2);
        task.putArray("assigneeIds"); task.putArray("checklist"); task.putNull("dueDate");
        String id=body(call(post("/api/tasks"),root,task,201)).path("id").asText();
        call(patch("/api/tasks/"+id),root,Map.of("title","Renamed Task"),200);
        call(delete("/api/tasks/"+id),root,null,204);
        var events=body(call(get("/api/audit").param("entity","tasks").param("q",id),root,null,200));
        assertThat(events.path("total").asInt()).isEqualTo(3);
        assertThat(events.path("items").get(0).path("action").asText()).isEqualTo("exclusao");
        assertThat(events.path("items").get(0).path("targetName").asText()).isEqualTo("Renamed Task");
        for (int i=0;i<55;i++) sql.update("INSERT INTO ideas(id,author_id,title,description,category,created_at) VALUES(?,?,?,?,'guia',current_date)",UUID.randomUUID(),INITIAL,"Page test "+i,"");
        var page1=body(call(get("/api/audit").param("q","Page test"),root,null,200));
        var page2=body(call(get("/api/audit").param("q","Page test").param("page","2"),root,null,200));
        assertThat(page1.path("items").size()).isEqualTo(50);
        assertThat(page2.path("items").size()).isEqualTo(5);
        assertThat(page1.path("total").asInt()).isEqualTo(55);
        assertThat(page1.path("items").findValuesAsText("id")).doesNotContainAnyElementsOf(page2.path("items").findValuesAsText("id"));
    }

    @Test @Order(8) void dateFiltersRespectBrasiliaAndForwardedIpRequiresRailway() throws Exception {
        for (String timestamp:java.util.List.of("2026-10-01T00:00:00-03:00","2026-10-01T23:59:59-03:00","2026-10-02T00:00:00-03:00")) {
            sql.update("INSERT INTO audit_logs(created_at,actor_name,action,entity,target_name) VALUES(?::timestamptz,'Sistema','criacao','ideas','Date boundary')",timestamp);
        }
        var entries=body(call(get("/api/audit").param("q","Date boundary").param("from","2026-10-01").param("to","2026-10-01"),root,null,200));
        assertThat(entries.path("total").asInt()).isEqualTo(2);
        var manager=new org.springframework.jdbc.support.JdbcTransactionManager(sql.getDataSource());
        var store=new Store(sql,json);
        var local=new AuditService(store,manager,"");
        var railway=new AuditService(store,manager,"production");
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");request.addHeader("X-Portal-Client-IP","203.0.113.12");
        assertThat(local.ip(request)).isEqualTo("127.0.0.1");
        assertThat(railway.ip(request)).isEqualTo("203.0.113.12");
        request.removeHeader("X-Portal-Client-IP");request.addHeader("X-Portal-Client-IP","attacker.example");
        assertThat(railway.ip(request)).isNull();
        String secret="not-a-real-token-hash";
        sql.update("INSERT INTO invitations VALUES(?,?,now()+interval '1 hour')",secret,PENDING);
        var invitations=body(call(get("/api/audit").param("entity","invitations"),root,null,200));
        assertThat(invitations.path("total").asInt()).isEqualTo(1);
        assertThat(invitations.toString()).doesNotContain(secret,"token_hash");
    }

    @Test @Order(9) void individualShortcutSavePersistsAndIsAudited() throws Exception {
        call(patch("/api/settings/shortcuts/sc-drive"),root,Map.of("url","https://drive.google.com/drive/folders/test"),200);
        var saved=body(call(get("/api/settings/shortcuts"),root,null,200));
        assertThat(saved.findValuesAsText("url")).contains("https://drive.google.com/drive/folders/test");
        assertThat(sql.queryForObject("SELECT url FROM shortcuts WHERE id='sc-drive'",String.class)).isEqualTo("https://drive.google.com/drive/folders/test");
        user=login("user@example.test","new-user-test-password");
        call(patch("/api/settings/shortcuts/sc-drive"),user,Map.of("url","https://example.test"),403);
        call(patch("/api/settings/shortcuts/sc-drive"),root,Map.of("url","javascript:alert(1)"),400);
        assertThat(body(call(get("/api/audit").param("entity","shortcuts").param("action","edicao").param("q","Google Drive"),root,null,200)).path("total").asInt()).isEqualTo(1);
    }

    @Test @Order(10) void preferencesPersistByAccountAndAuditOnlyChanges() throws Exception {
        var preferences=json.createObjectNode().put("theme","light").put("reducedMotion",true);
        preferences.set("notifications",json.valueToTree(Map.of("taskAssigned",true,"taskDue",true,"projectUpdates",false,"newsDigest",false,"email",false)));
        call(put("/api/me/preferences"),root,preferences,200);
        assertThat(body(call(get("/api/me/preferences"),root,null,200)).path("theme").asText()).isEqualTo("light");
        assertThat(body(call(get("/api/me/preferences"),user,null,200)).path("theme").asText()).isEqualTo("dark");
        int count=sql.queryForObject("SELECT count(*) FROM audit_logs WHERE entity='member_preferences'",Integer.class);
        call(put("/api/me/preferences"),root,preferences,200);
        assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE entity='member_preferences'",Integer.class)).isEqualTo(count);
        preferences.put("theme","system");call(put("/api/me/preferences"),root,preferences,200);
        var entry=body(call(get("/api/audit").param("entity","member_preferences").param("action","edicao"),root,null,200)).path("items").get(0);
        assertThat(entry.path("actorId").asText()).isEqualTo(INITIAL.toString());
    }

    @Test @Order(11) void libraryUploadDownloadDeleteUseDatabaseAndDoNotLeakBytesToAudit() throws Exception {
        String content="file-content-kept-out-of-audit";
        String id=body(call(post("/api/library/files"),user,Map.of("name","test-file.txt","folderId","geral","mediaType","text/plain","content",java.util.Base64.getEncoder().encodeToString(content.getBytes())),201)).path("id").asText();
        assertThat(sql.queryForObject("SELECT convert_from(content,'UTF8') FROM library_files WHERE id=?",String.class,UUID.fromString(id))).isEqualTo(content);
        var file=call(get("/api/library/files/"+id+"/content").param("download","true"),root,null,200).getResponse();
        assertThat(file.getContentAsString()).isEqualTo(content);
        assertThat(file.getHeader("Content-Disposition")).contains("attachment");
        call(get("/api/library/files/"+id+"/content"),null,null,401);
        var items=body(call(get("/api/audit").param("entity","library_files"),root,null,200));
        assertThat(items.toString()).doesNotContain(content,java.util.Base64.getEncoder().encodeToString(content.getBytes()));
        call(delete("/api/library/files/"+id),user,null,204);
        assertThat(sql.queryForObject("SELECT count(*) FROM library_files WHERE id=?",Integer.class,UUID.fromString(id))).isZero();
        assertThat(body(call(get("/api/audit").param("entity","library_files").param("action","exclusao"),root,null,200)).path("total").asInt()).isEqualTo(1);
    }

    @Test @Order(12) void projectEditingMilestonesAndTaskNotificationsPersist() throws Exception {
        var form=json.createObjectNode().put("name","Complete project").put("subtitle","test").put("description","real persistence").put("icon","code").put("color","#2563eb").put("dueDate","2026-12-30").put("status","em_andamento");
        form.putArray("ownerIds").add(INITIAL.toString());
        form.putArray("milestones").addObject().put("title","Stage one").put("dueDate","2026-12-20");
        String id=body(call(post("/api/projects"),root,form,201)).path("id").asText();
        var saved=body(call(get("/api/projects/"+id),root,null,200));
        assertThat(saved.path("status").asText()).isEqualTo("em_andamento");
        String milestone=saved.path("milestones").get(0).path("id").asText();
        call(patch("/api/projects/"+id+"/milestones/"+milestone),root,Map.of(),200);
        call(patch("/api/projects/"+id),root,Map.of("name","Renamed complete project","status","pausado"),200);
        assertThat(body(call(get("/api/projects/"+id),root,null,200)).path("milestones").get(0).path("done").asBoolean()).isTrue();
        assertThat(sql.queryForObject("SELECT status FROM projects WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("pausado");
        call(patch("/api/projects/"+id),user,Map.of("status","concluido"),403);
        var task=json.createObjectNode().put("title","Assigned persistent task").put("description","").put("projectId",id).put("category","programacao").put("status","a_fazer").put("priority","alta").put("open",false).put("maxAssignees",2);
        task.putArray("assigneeIds").add(userId);task.putArray("checklist").addObject().put("id","check1").put("text","Verify persistence").put("done",false);task.putNull("dueDate");
        String taskId=body(call(post("/api/tasks"),root,task,201)).path("id").asText();
        var checklist=json.createArrayNode().addObject().put("id","check1").put("text","Verify persistence").put("done",true);
        call(patch("/api/tasks/"+taskId),user,Map.of("checklist",java.util.List.of(checklist),"status","em_andamento"),200);
        assertThat(body(call(get("/api/notifications"),user,null,200)).findValuesAsText("title")).contains("Nova tarefa atribuída");
        call(post("/api/notifications/read-all"),user,Map.of(),204);
        assertThat(sql.queryForObject("SELECT count(*) FROM notifications WHERE member_id=? AND NOT read",Integer.class,UUID.fromString(userId))).isZero();
    }

    @Test @Order(13) void newGeneralCanLoginAndOriginalGeneralCanBecomeRegularAdmin() throws Exception {
        call(patch("/api/users/"+PENDING),root,Map.of("role","superadmin"),200);
        Cookie boss=login("boss@example.test","pending-test-password");
        assertThat(body(call(get("/api/auth/me"),boss,null,200)).path("role").asText()).isEqualTo("superadmin");
        call(patch("/api/users/"+INITIAL),root,Map.of("role","admin"),200);
        assertThat(body(call(get("/api/auth/me"),root,null,200)).path("role").asText()).isEqualTo("admin");
        call(patch("/api/users/"+PENDING),root,Map.of("role","usuario"),403);
        call(patch("/api/users/"+PENDING),boss,Map.of("role","admin"),409);
    }

    @Test @Order(14) void remindersRespectPreferencesAreDeduplicatedAndAuditedAsSystem() throws Exception {
        sql.update("UPDATE tasks SET due_date=?,status='em_andamento' WHERE title='Assigned persistent task'",Input.today().plusDays(1));
        jobs.deliver();jobs.deliver();
        assertThat(sql.queryForObject("SELECT count(*) FROM notifications WHERE dedup_key LIKE 'due:%' AND member_id=?",Integer.class,UUID.fromString(userId))).isEqualTo(1);
        var audit=body(call(get("/api/audit").param("entity","notifications").param("q","Prazo próximo"),root,null,200));
        assertThat(audit.path("items").get(0).path("actorName").asText()).isEqualTo("Sistema");
        assertThat(audit.path("items").get(0).path("ip").isNull()).isTrue();
        var preferences=json.createObjectNode().put("theme","dark").put("reducedMotion",false);
        preferences.set("notifications",json.valueToTree(Map.of("taskAssigned",false,"taskDue",false,"projectUpdates",false,"newsDigest",false,"email",false)));
        call(put("/api/me/preferences"),user,preferences,200);
        sql.update("UPDATE tasks SET due_date=? WHERE title='Assigned persistent task'",Input.today().plusDays(2));
        jobs.deliver();
        assertThat(sql.queryForObject("SELECT count(*) FROM notifications WHERE dedup_key LIKE 'due:%' AND member_id=?",Integer.class,UUID.fromString(userId))).isEqualTo(1);
        ((com.fasterxml.jackson.databind.node.ObjectNode)preferences.path("notifications")).put("email",true);
        call(put("/api/me/preferences"),user,preferences,409);
    }

    @Test @Order(15) void backlogSlotsMovementAndChecklistPersistWithPermissionChecks() throws Exception {
        var form=json.createObjectNode().put("title","Open backlog slot").put("description","").put("category","conteudo").put("status","backlog").put("priority","media").put("open",true).put("maxAssignees",1);
        form.putArray("assigneeIds");form.putArray("checklist");form.putNull("projectId");form.putNull("dueDate");
        String id=body(call(post("/api/tasks"),root,form,201)).path("id").asText();
        var accepted=body(call(post("/api/tasks/"+id+"/accept"),user,Map.of(),200));
        assertThat(accepted.path("status").asText()).isEqualTo("a_fazer");assertThat(accepted.path("open").asBoolean()).isFalse();
        assertThat(accepted.path("assigneeIds").size()).isEqualTo(1);
        call(post("/api/tasks/"+id+"/accept"),root,Map.of(),409);
        var move=json.createObjectNode().put("status","em_andamento");move.putNull("beforeId");
        call(patch("/api/tasks/"+id+"/move"),user,move,204);
        assertThat(sql.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("em_andamento");
        call(patch("/api/tasks/"+id),user,Map.of("title","Not allowed"),403);
        call(delete("/api/tasks/"+id),user,null,403);
        call(delete("/api/tasks/"+id),root,null,204);
    }

    @Test @Order(16) void shortcutsCanBeCreatedEditedWithLogoAndRemoved() throws Exception {
        String logo="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        var created=body(call(post("/api/settings/shortcuts"),root,Map.of("label","","url","","image",""),201));
        String id=created.path("id").asText();
        assertThat(id).startsWith("sc-");
        assertThat(created.path("label").asText()).isEqualTo("Novo atalho");
        assertThat(created.path("icon").asText()).isEqualTo("link");
        var list=body(call(get("/api/settings/shortcuts"),root,null,200));
        assertThat(list.get(list.size()-1).path("id").asText()).isEqualTo(id);
        var edited=body(call(patch("/api/settings/shortcuts/"+id),root,Map.of("label","Canva","url","https://www.canva.com/","image",logo),200));
        assertThat(edited.path("label").asText()).isEqualTo("Canva");
        assertThat(edited.path("image").asText()).isEqualTo(logo);
        call(patch("/api/settings/shortcuts/"+id),root,Map.of("image","data:image/svg+xml;base64,PHN2Zz4="),400);
        call(patch("/api/settings/shortcuts/"+id),root,Map.of("label",""),400);
        call(post("/api/settings/shortcuts"),user,Map.of("label","X"),403);
        call(delete("/api/settings/shortcuts/"+id),user,null,403);
        call(delete("/api/settings/shortcuts/"+id),root,null,204);
        call(delete("/api/settings/shortcuts/"+id),root,null,404);
        assertThat(sql.queryForObject("SELECT count(*) FROM shortcuts WHERE id=?",Integer.class,id)).isZero();
    }

}
