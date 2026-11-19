package com.firewalldo.portal;

import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"SESSION_SECURE=false","ADMIN_EMAIL=push@example.test","ADMIN_PASSWORD=push-test-password","PUSH_INITIAL_DELAY_MS=3600000","RADAR_INITIAL_DELAY_MS=3600000"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PushIntegrationTest {
    static final EmbeddedPostgres PG;
    static {try{PG=EmbeddedPostgres.builder().setServerConfig("unix_socket_directories","").setPort(0).start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"postgres");}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate sql;@Autowired ObjectMapper json;@Autowired PushDeliveryService jobs;
    @MockitoBean PushSender sender;
    static Cookie root,user;static String userId,pub,auth;static UUID device=UUID.randomUUID();
    @BeforeEach void resetSender(){reset(sender);when(sender.send(any(),anyString())).thenReturn(201);}
    JsonNode call(MockHttpServletRequestBuilder req,Cookie cookie,Object body,int expected)throws Exception{req.contentType("application/json");if(cookie!=null)req.cookie(cookie);if(body!=null)req.content(json.writeValueAsBytes(body));var r=mvc.perform(req).andReturn().getResponse();assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(expected);return r.getContentAsByteArray().length==0?json.nullNode():json.readTree(r.getContentAsByteArray());}
    Cookie login(String email,String password)throws Exception{return mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsBytes(Map.of("email",email,"senha",password)))).andReturn().getResponse().getCookie("fw_session");}
    Map<String,Object> subscription(UUID id){return Map.of("id",id.toString(),"device","Android · teste","endpoint","https://fcm.googleapis.com/fcm/send/fixture-"+id,"keys",Map.of("p256dh",pub,"auth",auth));}
    UUID event(String title){UUID id=UUID.randomUUID();sql.update("INSERT INTO notifications(id,member_id,kind,title,description) VALUES(?,?,'tarefa',?,'Nova atividade no portal')",id,UUID.fromString(userId),title);return id;}
    @Test @Order(1) void generatesStableVapidKeysAndDoesNotExposeSecrets()throws Exception{
        root=login("push@example.test","push-test-password");var account=call(post("/api/users"),root,Map.of("name","Push User","email","push-user@example.test","title","User","role","usuario","senha","push-user-password"),201);userId=account.path("id").asText();user=login("push-user@example.test","push-user-password");
        var state=call(get("/api/push"),user,null,200);pub=state.path("publicKey").asText();auth=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);String privateKey=sql.queryForObject("SELECT private_key FROM push_config",String.class);
        assertThat(Base64.getUrlDecoder().decode(pub)).hasSize(65);assertThat(state.toString()).doesNotContain(privateKey,"private_key","auth");jobs.run(null);assertThat(sql.queryForObject("SELECT public_key FROM push_config",String.class)).isEqualTo(pub);
        call(get("/api/push"),null,null,401);call(post("/api/push/test"),user,Map.of(),400);
    }
    @Test @Order(2) void subscribesPerAccountAndRejectsPrivateOrUnrecognizedEndpoints()throws Exception{
        call(post("/api/push/subscriptions"),user,subscription(device),200);call(post("/api/push/subscriptions"),user,subscription(device),200);
        var state=call(get("/api/push"),user,null,200);assertThat(state.path("devices").size()).isEqualTo(1);assertThat(state.toString()).doesNotContain("fcm.googleapis.com","p256dh","private_key");
        assertThat(call(get("/api/push"),root,null,200).path("devices").size()).isZero();call(post("/api/push/subscriptions"),root,subscription(device),409);call(delete("/api/push/subscriptions/"+device),root,null,204);assertThat(sql.queryForObject("SELECT count(*) FROM push_subscriptions",Integer.class)).isEqualTo(1);
        var bad=new HashMap<>(subscription(UUID.randomUUID()));bad.put("endpoint","http://127.0.0.1/");call(post("/api/push/subscriptions"),user,bad,400);bad.put("endpoint","https://attacker.example.test/");call(post("/api/push/subscriptions"),user,bad,400);
        var audit=call(get("/api/audit").param("entity","push_subscriptions"),root,null,200);assertThat(audit.path("total").asInt()).isEqualTo(1);assertThat(audit.toString()).doesNotContain(auth,"endpoint","p256dh");
    }
    @Test @Order(3) void testNotificationQueuesSendsAndDoesNotDuplicateAcceptedDeliveries()throws Exception{
        call(post("/api/push/test"),user,Map.of(),202);assertThat(sql.queryForObject("SELECT status FROM push_deliveries",String.class)).isEqualTo("queued");jobs.deliver();jobs.deliver();
        verify(sender,times(1)).send(any(),argThat(payload->payload.contains(userId)&&payload.contains("Teste de notificação push")));
        assertThat(sql.queryForObject("SELECT status FROM push_deliveries",String.class)).isEqualTo("accepted");assertThat(call(get("/api/push"),user,null,200).path("deliveries").get(0).path("message").asText()).contains("Provedor aceitou");
        call(post("/api/push/test"),user,Map.of(),409);
        var audit=call(get("/api/audit").param("entity","push_deliveries").param("action","edicao"),root,null,200);assertThat(audit.path("items").get(0).path("actorName").asText()).isEqualTo("Sistema");assertThat(audit.toString()).doesNotContain("fcm.googleapis.com",auth);
    }
    @Test @Order(4) void temporaryFailureRetriesAndExpiredEndpointIsRemovedWithoutLosingHistory()throws Exception{
        event("Retry test");when(sender.send(any(),anyString())).thenReturn(429,201);jobs.deliver();assertThat(sql.queryForObject("SELECT status FROM push_deliveries WHERE title='Retry test'",String.class)).isEqualTo("queued");sql.update("UPDATE push_deliveries SET next_attempt_at=now() WHERE title='Retry test'");jobs.deliver();assertThat(sql.queryForObject("SELECT attempts FROM push_deliveries WHERE title='Retry test'",Integer.class)).isEqualTo(2);
        event("Expired endpoint");when(sender.send(any(),anyString())).thenReturn(410);jobs.deliver();assertThat(sql.queryForObject("SELECT count(*) FROM push_subscriptions",Integer.class)).isZero();assertThat(sql.queryForObject("SELECT status FROM push_deliveries WHERE title='Expired endpoint'",String.class)).isEqualTo("expired");assertThat(sql.queryForObject("SELECT count(*) FROM push_deliveries",Integer.class)).isEqualTo(3);
        call(post("/api/push/subscriptions"),user,subscription(device),200);
    }
    @Test @Order(5) void actualTaskAssignmentCreatesPushAndPreferencesCanDisableIt()throws Exception{
        var task=json.createObjectNode().put("title","New assigned task").put("description","").put("category","conteudo").put("status","a_fazer").put("priority","alta").put("open",false).put("maxAssignees",1);task.putNull("projectId");task.putNull("dueDate");task.putArray("assigneeIds").add(userId);task.putArray("checklist");
        call(post("/api/tasks"),root,task,201);assertThat(sql.queryForObject("SELECT count(*) FROM push_deliveries WHERE title='Nova tarefa atribuída'",Integer.class)).isEqualTo(1);jobs.deliver();verify(sender).send(any(),argThat(payload->payload.contains("New assigned task")));
        var prefs=call(get("/api/me/preferences"),user,null,200);((com.fasterxml.jackson.databind.node.ObjectNode)prefs.path("notifications")).put("taskAssigned",false);call(put("/api/me/preferences"),user,prefs,200);task.put("title","Muted assignment");call(post("/api/tasks"),root,task,201);assertThat(sql.queryForObject("SELECT count(*) FROM push_deliveries WHERE title='Nova tarefa atribuída'",Integer.class)).isEqualTo(1);
        ((com.fasterxml.jackson.databind.node.ObjectNode)prefs.path("notifications")).put("taskAssigned",true);call(put("/api/me/preferences"),user,prefs,200);task.put("open",true).put("status","backlog");task.putArray("assigneeIds");call(post("/api/tasks"),root,task,201);assertThat(sql.queryForObject("SELECT count(*) FROM push_deliveries WHERE title='Nova tarefa disponível'",Integer.class)).isEqualTo(1);
    }
    @Test @Order(6) void preparesRealEncryptedPayloadWithVapidAndNoPlaintext()throws Exception{
        var db=new Store(sql,json);var transport=new EncryptedPushSender(db,new org.springframework.mock.env.MockEnvironment());
        var node=json.valueToTree(Map.of("endpoint","https://fcm.googleapis.com/fcm/send/fixture","p256dh",pub,"auth",auth));String text="Confidential notification body";
        var request=transport.prepare(node,text);assertThat(request.getHeaders().get("Content-Encoding")).isEqualTo("aes128gcm");assertThat(request.getHeaders().get("Urgency")).isEqualTo("high");assertThat(request.getHeaders().get("Authorization")).startsWith("vapid ").contains("k="+pub);assertThat(new String(request.getBody(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(text);assertThat(request.getBody().length).isGreaterThan(text.length());
    }
    @Test @Order(7) void logoutRevokesOnlyCurrentDeviceAndCancelsQueuedPush()throws Exception{
        UUID another=UUID.randomUUID();call(post("/api/push/subscriptions"),user,subscription(another),200);event("Before logout");
        call(post("/api/auth/logout").header("X-Push-Device",device.toString()),user,Map.of(),204);
        assertThat(sql.queryForObject("SELECT count(*) FROM push_subscriptions WHERE id=?",Integer.class,device)).isZero();assertThat(sql.queryForObject("SELECT count(*) FROM push_subscriptions WHERE id=?",Integer.class,another)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM push_deliveries WHERE title='Before logout' AND status='expired'",Integer.class)).isEqualTo(1);
        jobs.deliver();verify(sender,atLeastOnce()).send(any(),anyString());
    }
}
