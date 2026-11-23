package com.firewalldo.portal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
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

@SpringBootTest(properties={"SESSION_SECURE=false","ADMIN_EMAIL=backlog1111@example.test","ADMIN_PASSWORD=backlog1111-test-password","AGENDA_INITIAL_DELAY_MS=3600000","NOTIFICATION_INITIAL_DELAY_MS=3600000","FINANCE_INITIAL_DELAY_MS=3600000","PUSH_INITIAL_DELAY_MS=3600000"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Backlog1111IntegrationTest {
    static final EmbeddedPostgres PG;
    static final UUID SEED=UUID.randomUUID(), STUCK=UUID.randomUUID(), PARTIAL=UUID.randomUUID(), FULL=UUID.randomUUID();
    static final Map<UUID,Long> TICKETS=new HashMap<>();
    static {
        try {
            PG=EmbeddedPostgres.builder().setServerConfig("unix_socket_directories","").setPort(0).start();
            Flyway.configure().dataSource(PG.getPostgresDatabase()).locations("classpath:db/migration").target("9").load().migrate();
            var sql=new JdbcTemplate(PG.getPostgresDatabase());
            sql.update("INSERT INTO members(id,name,email,role) VALUES(?,'Legacy seeder','legacy1111@example.test','usuario')",SEED);
            for(UUID id:List.of(STUCK,PARTIAL,FULL)) {
                boolean accepted=!id.equals(STUCK);
                sql.update("INSERT INTO tasks(id,title,description,category,status,priority,assignee_ids,open,max_assignees,checklist,created_by,created_at,updated_at,position) VALUES(?,'Legacy backlog','','gestao','backlog','media',?::uuid[],false,?,'[]',?,current_date,current_date,1)",id,Store.array(accepted?List.of(SEED):List.of()),id.equals(PARTIAL)?2:1,SEED);
                TICKETS.put(id,sql.queryForObject("SELECT ticket_number FROM tasks WHERE id=?",Long.class,id));
            }
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate sql;
    Cookie root,a,b,c,outsider,admin; String rootId,aId,bId,cId,outsiderId,adminId;
    JsonNode call(MockHttpServletRequestBuilder req,Cookie cookie,Object body,int expected)throws Exception {
        req.contentType("application/json");if(cookie!=null)req.cookie(cookie);if(body!=null)req.content(json.writeValueAsBytes(body));
        var result=mvc.perform(req).andReturn().getResponse();
        assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(expected);
        return result.getContentAsByteArray().length==0?json.nullNode():json.readTree(result.getContentAsByteArray());
    }
    Cookie login(String email,String password)throws Exception{return mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsBytes(Map.of("email",email,"senha",password)))).andReturn().getResponse().getCookie("fw_session");}
    String member(String name,String role)throws Exception{return call(post("/api/users"),root,Map.of("name",name,"email",name+"@example.test","role",role,"title","","senha","backlog-member-password"),201).path("id").asText();}
    @BeforeAll void accounts()throws Exception{
        root=login("backlog1111@example.test","backlog1111-test-password");rootId=call(get("/api/auth/me"),root,null,200).path("id").asText();
        aId=member("candidate-a1111","usuario");bId=member("candidate-b1111","usuario");cId=member("candidate-c1111","usuario");outsiderId=member("outsider1111","usuario");adminId=member("admin1111","admin");
        a=login("candidate-a1111@example.test","backlog-member-password");b=login("candidate-b1111@example.test","backlog-member-password");c=login("candidate-c1111@example.test","backlog-member-password");outsider=login("outsider1111@example.test","backlog-member-password");admin=login("admin1111@example.test","backlog-member-password");
    }
    ObjectNode form(int slots,List<String> eligible){
        var f=json.createObjectNode().put("title","Backlog pool "+UUID.randomUUID()).put("description","").putNull("projectId").put("category","gestao").put("status","backlog").put("priority","media").put("open",false).put("maxAssignees",slots).putNull("dueDate");
        f.putArray("checklist");f.putArray("assigneeIds");f.set("eligibleAssigneeIds",json.valueToTree(eligible));return f;
    }
    JsonNode create(int slots,List<String> eligible)throws Exception{return call(post("/api/tasks"),root,form(slots,eligible),201);}
    JsonNode accepted(JsonNode task,Cookie cookie,int expected)throws Exception{return call(post("/api/tasks/"+task.path("id").asText()+"/accept"),cookie,Map.of(),expected);}

    @Test void migratesBlockedLegacyBacklogWithoutLosingAcceptedPeopleOrTickets()throws Exception {
        assertThat(sql.queryForObject("SELECT open FROM tasks WHERE id=?",Boolean.class,STUCK)).isTrue();
        assertThat(sql.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,PARTIAL)).isEqualTo("backlog");
        assertThat(sql.queryForObject("SELECT cardinality(assignee_ids) FROM tasks WHERE id=?",Integer.class,PARTIAL)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT open FROM tasks WHERE id=?",Boolean.class,PARTIAL)).isTrue();
        assertThat(sql.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,FULL)).isEqualTo("a_fazer");
        for(var item:TICKETS.entrySet())assertThat(sql.queryForObject("SELECT ticket_number FROM tasks WHERE id=?",Long.class,item.getKey())).isEqualTo(item.getValue());
    }
    @Test void threeCandidatesForOneSlotFirstAcceptWinsAndOthersStayUnassigned()throws Exception {
        var task=create(1,List.of(aId,bId,cId));
        assertThat(task.path("assigneeIds")).isEmpty();assertThat(task.path("eligibleAssigneeIds")).hasSize(3);assertThat(task.path("open").asBoolean()).isTrue();
        assertThat(call(get("/api/tasks"),a,null,200).findValuesAsText("id")).contains(task.path("id").asText());
        assertThat(call(get("/api/tasks"),outsider,null,200).findValuesAsText("id")).doesNotContain(task.path("id").asText());
        accepted(task,outsider,403);accepted(task,root,403);accepted(task,admin,403);
        var won=accepted(task,a,200);assertThat(won.path("status").asText()).isEqualTo("a_fazer");assertThat(won.path("assigneeIds").get(0).asText()).isEqualTo(aId);
        accepted(task,b,409);accepted(task,c,409);
        var retry=accepted(task,a,200);assertThat(retry.path("assigneeIds")).hasSize(1);assertThat(retry.path("ticketNumber")).isEqualTo(task.path("ticketNumber"));
        assertThat(call(get("/api/tasks"),b,null,200).findValuesAsText("id")).doesNotContain(task.path("id").asText());
    }
    @Test void waitsForTwoRealAcceptancesFromThreeCandidatesBeforeAnyoneCanStart()throws Exception {
        var task=create(2,List.of(aId,bId,cId));String id=task.path("id").asText();
        var first=accepted(task,a,200);assertThat(first.path("status").asText()).isEqualTo("backlog");assertThat(first.path("open").asBoolean()).isTrue();assertThat(first.path("assigneeIds")).hasSize(1);
        call(patch("/api/tasks/"+id+"/move"),a,Map.of("status","em_andamento"),400);
        call(patch("/api/tasks/"+id),root,Map.of("status","a_fazer"),400);
        var second=accepted(task,c,200);assertThat(second.path("status").asText()).isEqualTo("a_fazer");assertThat(second.path("open").asBoolean()).isFalse();
        assertThat(second.path("assigneeIds")).containsExactly(json.getNodeFactory().textNode(aId),json.getNodeFactory().textNode(cId));
        accepted(task,b,409);call(patch("/api/tasks/"+id+"/move"),b,Map.of("status","em_andamento"),403);
        call(patch("/api/tasks/"+id+"/move"),a,Map.of("status","em_andamento"),204);
    }
    @Test void noCandidateSelectionMeansAnyoneActiveCanAcceptEvenWithOldOpenFlagOff()throws Exception {
        var task=create(1,List.of());assertThat(task.path("open").asBoolean()).isTrue();
        assertThat(accepted(task,outsider,200).path("assigneeIds").get(0).asText()).isEqualTo(outsiderId);
    }
    @Test void legacyClientsCandidateSelectionDoesNotOccupySlots()throws Exception {
        var f=form(1,List.of());f.remove("eligibleAssigneeIds");f.set("assigneeIds",json.valueToTree(List.of(aId,bId,cId)));
        var task=call(post("/api/tasks"),root,f,201);assertThat(task.path("assigneeIds")).isEmpty();assertThat(task.path("eligibleAssigneeIds")).hasSize(3);
        assertThat(accepted(task,b,200).path("assigneeIds").get(0).asText()).isEqualTo(bId);
    }
    @Test void editsCandidatesSeparatelyAndPreservesConfirmedAcceptance()throws Exception {
        var task=create(2,List.of(aId,bId));String id=task.path("id").asText();accepted(task,a,200);
        var edited=call(patch("/api/tasks/"+id),admin,Map.of("eligibleAssigneeIds",List.of(aId,bId,cId)),200);
        assertThat(edited.path("assigneeIds")).hasSize(1);assertThat(edited.path("eligibleAssigneeIds")).hasSize(3);
        call(patch("/api/tasks/"+id),admin,Map.of("assigneeIds",List.of(bId)),400);
        var ready=call(patch("/api/tasks/"+id),admin,Map.of("maxAssignees",1),200);
        assertThat(ready.path("status").asText()).isEqualTo("a_fazer");assertThat(ready.path("assigneeIds").get(0).asText()).isEqualTo(aId);
    }
    @Test void concurrentAcceptsCannotOverfillAndSamePersonCannotConsumeTwoSlots()throws Exception {
        for(int slots:List.of(1,2)){
            var task=create(slots,List.of(aId,bId,cId));String url="/api/tasks/"+task.path("id").asText()+"/accept";
            var pool=Executors.newFixedThreadPool(3);
            try{
                var futures=new ArrayList<Future<Integer>>();
                for(Cookie actor:List.of(a,b,c))futures.add(pool.submit(()->mvc.perform(post(url).cookie(actor).contentType("application/json").content("{}")).andReturn().getResponse().getStatus()));
                var statuses=new ArrayList<Integer>();for(var result:futures)statuses.add(result.get(10,TimeUnit.SECONDS));
                assertThat(statuses.stream().filter(s->s==200).count()).isEqualTo(slots);assertThat(statuses.stream().filter(s->s==409).count()).isEqualTo(3-slots);
                assertThat(sql.queryForObject("SELECT cardinality(assignee_ids) FROM tasks WHERE id=?",Integer.class,UUID.fromString(task.path("id").asText()))).isEqualTo(slots);
            }finally{pool.shutdownNow();}
        }
        var task=create(2,List.of(aId,bId));var pool=Executors.newFixedThreadPool(2);
        try{var one=pool.submit(()->accepted(task,a,200));var two=pool.submit(()->accepted(task,a,200));assertThat(one.get().path("assigneeIds")).hasSize(1);assertThat(two.get().path("assigneeIds")).hasSize(1);}finally{pool.shutdownNow();}
    }
    @Test void candidateNotificationsAndAuditRecordTheCorrectPoolWithoutClaimingAssignment()throws Exception {
        var task=create(1,List.of(aId,bId,cId));String id=task.path("id").asText(),title=task.path("title").asText();
        assertThat(sql.queryForObject("SELECT count(*) FROM notifications WHERE description=? AND title='Nova tarefa disponível'",Integer.class,title)).isEqualTo(3);
        assertThat(sql.queryForObject("SELECT count(*) FROM notifications WHERE description=? AND member_id=?",Integer.class,title,UUID.fromString(outsiderId))).isZero();
        var item=call(get("/api/audit").param("entity","tasks").param("q",id).param("action","criacao"),root,null,200).path("items").get(0);
        assertThat(item.path("details").path("changes").path("eligible_assignee_ids").path("after")).hasSize(3);
        assertThat(item.path("details").path("changes").path("assignee_ids").path("after")).isEmpty();
    }
}
