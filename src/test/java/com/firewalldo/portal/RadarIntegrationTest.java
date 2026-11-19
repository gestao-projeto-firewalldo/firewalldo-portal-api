package com.firewalldo.portal;

import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"SESSION_SECURE=false","ADMIN_EMAIL=radar@example.test","ADMIN_PASSWORD=radar-test-password","OPENAI_API_KEY=fixture-only-not-a-real-key","RADAR_INITIAL_DELAY_MS=3600000"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RadarIntegrationTest {
    static final EmbeddedPostgres PG;
    static {try{PG=EmbeddedPostgres.builder().setServerConfig("unix_socket_directories","").setPort(0).start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"postgres");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate sql;
    @Autowired RadarService radar;
    @MockitoBean RadarNetwork network;
    static Cookie root,user;
    static String rootId;
    static byte[] png;
    JsonNode call(MockHttpServletRequestBuilder req,Cookie cookie,Object body,int status) throws Exception {
        req.contentType("application/json");if(cookie!=null)req.cookie(cookie);if(body!=null)req.content(json.writeValueAsBytes(body));
        var response=mvc.perform(req).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsByteArray().length==0?json.nullNode():json.readTree(response.getContentAsByteArray());
    }
    Cookie login(String email,String password) throws Exception {return mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsBytes(Map.of("email",email,"senha",password)))).andReturn().getResponse().getCookie("fw_session");}
    JsonNode settings(boolean enabled,boolean publish,boolean images) {
        return json.valueToTree(Map.of("enabled",enabled,"intervalMinutes",180,"lookbackHours",24,"maxItems",3,"criteria","Incidentes de segurança de alto impacto e novidades relevantes em inteligência artificial.","autoPublish",publish,"fetchImages",images));
    }
    void sources() throws Exception {call(put("/api/settings/radar-sources"),root,List.of(Map.of("id","fixture-source","name","Fixture News","description","Official fixture","url","https://news.example.test/","short","FN","active",true)),200);}
    JsonNode article(String slug) {
        return json.valueToTree(Map.of("title","Nova notícia de segurança "+slug,"summary","Resumo próprio e verificado da publicação.","body",List.of("A organização confirmou o incidente e publicou orientações para os administradores."),"category","ciberseguranca","url","https://news.example.test/"+slug,"publishedAt",OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).toString(),"imageQuery","network firewall","references",List.of(Map.of("title","Comunicado original","url","https://news.example.test/"+slug))));
    }
    void provider(String slug,boolean withImage) throws Exception {
        reset(network);
        when(network.openai(anyString(),anyString())).thenAnswer(invocation->{
            var request=json.readTree((String)invocation.getArgument(1));
            if(request.has("tools")) {
                assertThat(request.path("tools").get(0).path("filters").path("allowed_domains").get(0).asText()).isEqualTo("news.example.test");
                return json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","web_search_call","action",Map.of("sources",List.of(Map.of("url","https://news.example.test/"+slug)))),Map.of("type","message","content",List.of(Map.of("type","output_text","text","Relatório factual com comunicado e data verificados."))))));
            }
            assertThat(request.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
            return json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(Map.of("articles",List.of(article(slug))))))))));
        });
        when(network.get(anyString(),anyInt())).thenAnswer(invocation->{String url=invocation.getArgument(0);if(url.endsWith("/fixture.png"))return new RadarNetwork.Resource(png,"image/png",url);
            if(withImage)return new RadarNetwork.Resource("<html><head><meta content='/fixture.png' property='og:image'></head></html>".getBytes(),"text/html",url);
            throw ApiError.invalid("Imagem indisponível.");});
    }
    void waitFinished() throws Exception {for(int i=0;i<200;i++){if(sql.queryForObject("SELECT count(*) FROM radar_runs WHERE status='running'",Integer.class)==0)return;Thread.sleep(50);}throw new AssertionError("Collector did not finish");}
    void execute() {UUID id=UUID.randomUUID();sql.update("INSERT INTO radar_runs(id,status) VALUES(?,'running')",id);radar.execute(id);}

    @Test @Order(1) void configurationIsPrivateValidatedAndHasNoSecret() throws Exception {
        root=login("radar@example.test","radar-test-password");assertThat(root).isNotNull();rootId=call(get("/api/auth/me"),root,null,200).path("id").asText();
        call(post("/api/users"),root,Map.of("name","Radar User","email","reader@example.test","role","usuario","title","Reader","senha","reader-test-password"),201);user=login("reader@example.test","reader-test-password");
        call(get("/api/settings/radar"),user,null,403);call(put("/api/settings/radar"),user,settings(true,true,true),403);call(post("/api/settings/radar/collect"),user,Map.of(),403);
        var bad=(com.fasterxml.jackson.databind.node.ObjectNode)settings(true,true,true);bad.put("intervalMinutes",1);call(put("/api/settings/radar"),root,bad,400);
        sources();var state=call(put("/api/settings/radar"),root,settings(false,true,true),200);
        assertThat(state.path("keyConfigured").asBoolean()).isTrue();assertThat(state.toString()).doesNotContain("fixture-only-not-a-real-key");
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);png=bytes.toByteArray();
    }
    @Test @Order(2) void projectCategoryBrandTaskInheritanceAndProgressComeFromDatabase() throws Exception {
        var form=json.createObjectNode().put("name","Categorized project").put("subtitle","").put("description","").put("icon","code").put("color","#2563eb").put("dueDate",Input.today().plusDays(5).toString()).put("category","design").put("brand","Firewalldo");form.putArray("ownerIds").add(rootId);
        var project=call(post("/api/projects"),root,form,201);String id=project.path("id").asText();assertThat(project.path("progress").asInt()).isZero();
        assertThat(call(get("/api/projects/"+id),root,null,200).path("brand").asText()).isEqualTo("Firewalldo");
        var task=json.createObjectNode().put("title","Inherited design task").put("description","").put("projectId",id).put("status","a_fazer").put("priority","media").put("open",true).put("maxAssignees",1);task.putArray("assigneeIds");task.putArray("checklist");task.putNull("dueDate");
        var saved=call(post("/api/tasks"),root,task,201);assertThat(saved.path("category").asText()).isEqualTo("design");
        call(patch("/api/tasks/"+saved.path("id").asText()),root,Map.of("status","concluido"),200);assertThat(call(get("/api/projects/"+id),root,null,200).path("progress").asInt()).isEqualTo(100);
        task.put("category","programacao");call(post("/api/tasks"),root,task,201);assertThat(call(get("/api/projects/"+id),root,null,200).path("progress").asInt()).isEqualTo(50);
        call(patch("/api/projects/"+id),root,Map.of("category","marketing","brand","Zinid"),200);assertThat(sql.queryForObject("SELECT category FROM projects WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("marketing");
        assertThat(sql.queryForObject("SELECT category FROM tasks WHERE id=?",String.class,UUID.fromString(saved.path("id").asText()))).isEqualTo("design");
        call(patch("/api/projects/"+id),root,Map.of("category","made-up"),400);call(patch("/api/projects/"+id),user,Map.of("brand","Blocked"),403);
    }
    @Test @Order(3) void manualCollectionUsesCitedEvidencePersistsImageAndAuditsSystem() throws Exception {
        provider("first",true);
        call(post("/api/settings/radar/collect"),root,Map.of(),202);waitFinished();
        var news=call(get("/api/news"),user,null,200);assertThat(news.size()).isEqualTo(1);var item=news.get(0);
        assertThat(item.path("title").asText()).contains("first");assertThat(item.path("references").size()).isEqualTo(1);assertThat(item.path("imageUrl").asText()).startsWith("/api/news/");
        assertThat(sql.queryForObject("SELECT image_content FROM news WHERE id=?",byte[].class,UUID.fromString(item.path("id").asText()))).isEqualTo(png);
        var image=mvc.perform(get(item.path("imageUrl").asText()).cookie(user)).andReturn().getResponse();assertThat(image.getStatus()).isEqualTo(200);assertThat(image.getContentAsByteArray()).isEqualTo(png);assertThat(image.getContentType()).isEqualTo("image/png");
        var audit=call(get("/api/audit").param("entity","news"),root,null,200);assertThat(audit.path("items").get(0).path("actorName").asText()).isEqualTo("Sistema");assertThat(audit.path("items").get(0).path("ip").isNull()).isTrue();assertThat(audit.toString()).doesNotContain("image_content","fixture-only-not-a-real-key");
        assertThat(sql.queryForObject("SELECT status FROM radar_runs ORDER BY started_at DESC LIMIT 1",String.class)).isEqualTo("success");
        call(post("/api/settings/radar/collect"),root,Map.of(),409);
    }
    @Test @Order(4) void scheduledCollectionDeduplicatesAndSurvivesPersistedConfiguration() throws Exception {
        provider("first",true);call(put("/api/settings/radar"),root,settings(true,true,true),200);sql.update("UPDATE radar_settings SET next_run_at=now()-interval '1 minute'");radar.tick();waitFinished();
        assertThat(sql.queryForObject("SELECT count(*) FROM news",Integer.class)).isEqualTo(1);assertThat(sql.queryForObject("SELECT skipped FROM radar_runs ORDER BY started_at DESC LIMIT 1",Integer.class)).isEqualTo(1);
        assertThat(call(get("/api/settings/radar"),root,null,200).path("config").path("enabled").asBoolean()).isTrue();
        assertThat(sql.queryForObject("SELECT next_run_at>now() FROM radar_settings",Boolean.class)).isTrue();
    }
    @Test @Order(5) void approvalModeHidesDraftsUntilAdminPublishesAndSourcePauseHidesImages() throws Exception {
        provider("draft",true);call(put("/api/settings/radar"),root,settings(false,false,true),200);execute();
        var drafts=call(get("/api/news").param("drafts","true"),root,null,200);assertThat(drafts.size()).isEqualTo(1);var item=drafts.get(0);String id=item.path("id").asText();
        assertThat(call(get("/api/news"),user,null,200).size()).isEqualTo(1);call(get("/api/news").param("drafts","true"),user,null,403);call(post("/api/news/"+id+"/save"),user,Map.of(),404);
        assertThat(mvc.perform(get(item.path("imageUrl").asText()).cookie(user)).andReturn().getResponse().getStatus()).isEqualTo(404);
        call(post("/api/news/"+id+"/publish"),user,Map.of(),403);call(post("/api/news/"+id+"/publish"),root,Map.of(),200);assertThat(call(get("/api/news"),user,null,200).size()).isEqualTo(2);
        sql.update("UPDATE radar_sources SET active=false WHERE id='fixture-source'");assertThat(call(get("/api/news"),user,null,200).size()).isZero();
        assertThat(mvc.perform(get(item.path("imageUrl").asText()).cookie(root)).andReturn().getResponse().getStatus()).isEqualTo(404);sql.update("UPDATE radar_sources SET active=true WHERE id='fixture-source'");
    }
    @Test @Order(6) void noImageAndProviderFailureAreVisibleWithoutFakeSuccess() throws Exception {
        provider("noimage",false);call(put("/api/settings/radar"),root,settings(false,true,true),200);execute();
        assertThat(sql.queryForObject("SELECT image_content IS NULL FROM news WHERE url LIKE '%/noimage'",Boolean.class)).isTrue();assertThat(sql.queryForObject("SELECT message FROM radar_runs ORDER BY started_at DESC LIMIT 1",String.class)).contains("sem imagem");
        doThrow(ApiError.invalid("Limite ou saldo da API da OpenAI atingido.")).when(network).openai(anyString(),anyString());execute();
        assertThat(sql.queryForObject("SELECT status FROM radar_runs ORDER BY started_at DESC LIMIT 1",String.class)).isEqualTo("failed");assertThat(sql.queryForObject("SELECT count(*) FROM news",Integer.class)).isEqualTo(3);
        assertThat(call(get("/api/settings/radar"),root,null,200).toString()).contains("Limite ou saldo").doesNotContain("fixture-only-not-a-real-key");
    }
    @Test @Order(7) void uncitedOutputAndStaleDatesCannotBecomeNews() throws Exception {
        provider("uncited",false);
        reset(network);when(network.openai(anyString(),anyString())).thenAnswer(invocation->{var req=json.readTree((String)invocation.getArgument(1));String text=req.has("tools")?"Relatório":json.writeValueAsString(Map.of("articles",List.of(article("uncited"))));return json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",text))))));});
        execute();assertThat(sql.queryForObject("SELECT status FROM radar_runs ORDER BY started_at DESC LIMIT 1",String.class)).isEqualTo("failed");assertThat(sql.queryForObject("SELECT count(*) FROM news WHERE url LIKE '%uncited'",Integer.class)).isZero();
        provider("stale",false);
        reset(network);var stale=(com.fasterxml.jackson.databind.node.ObjectNode)article("stale");stale.put("publishedAt",OffsetDateTime.now().minusDays(5).toString());
        reset(network);when(network.openai(anyString(),anyString())).thenAnswer(invocation->{var req=json.readTree((String)invocation.getArgument(1));if(req.has("tools"))return json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","web_search_call","action",Map.of("sources",List.of(Map.of("url","https://news.example.test/stale")))),Map.of("type","message","content",List.of(Map.of("type","output_text","text","Relatório antigo"))))));return json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(Map.of("articles",List.of(stale)))))))));});
        execute();assertThat(sql.queryForObject("SELECT count(*) FROM news WHERE url LIKE '%stale'",Integer.class)).isZero();
    }
    @Test @Order(8) void networkRejectsPrivateEndpointsAndUnsafeImagesAndNormalizesTracking() throws Exception {
        for(String url:List.of("http://localhost/","http://127.0.0.1/","http://10.0.0.1/","http://100.64.1.1/","http://169.254.169.254/","http://[::1]/","http://[fc00::1]/","http://api.railway.internal/","https://x.example:8080/","file:///etc/passwd","https://key:secret@x.example/"))assertThatThrownBy(()->PublicRadarNetwork.publicUrl(url)).isInstanceOf(ApiError.class);
        assertThat(PublicRadarNetwork.isPublic(java.net.InetAddress.getByName("192.168.1.3"))).isFalse();assertThat(PublicRadarNetwork.isPublic(java.net.InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(RadarProvider.matches("https://example.com/","https://example.com.attacker.test/article")).isFalse();assertThat(RadarProvider.canonical("https://news.example.test/a?utm_source=x&b=1#title")).isEqualTo("https://news.example.test/a?b=1");
        when(network.get(anyString(),anyInt())).thenReturn(new RadarNetwork.Resource("<svg onload='bad()'>".getBytes(),"image/svg+xml","https://news.example.test/image"));
        assertThatThrownBy(()->new RadarImages(network,json).download("https://news.example.test/image","credit","https://news.example.test/",false)).isInstanceOf(ApiError.class);
        var env=new org.springframework.mock.env.MockEnvironment();var provider=new RadarProvider(network,json,env);assertThat(provider.configured()).isFalse();assertThatThrownBy(()->provider.search(settings(false,true,false),List.of(),List.of())).isInstanceOf(ApiError.class).hasMessageContaining("OPENAI_API_KEY");
    }
    @Test @Order(9) void imageSearchFallbackPersistsLicensedIllustrationWithAttribution() throws Exception {
        provider("illustration",false);
        doAnswer(invocation->{
            String url=invocation.getArgument(0);
            if(url.startsWith("https://commons.wikimedia.org/w/api.php"))return new RadarNetwork.Resource(json.writeValueAsBytes(Map.of("query",Map.of("pages",Map.of("1",Map.of("imageinfo",List.of(Map.of("thumburl","https://upload.wikimedia.org/fixture.png","descriptionurl","https://commons.wikimedia.org/wiki/File:Fixture.png","extmetadata",Map.of("LicenseShortName",Map.of("value","CC BY-SA 4.0"),"Artist",Map.of("value","<a>Fixture Photographer</a>"))))))))),"application/json",url);
            if(url.equals("https://upload.wikimedia.org/fixture.png"))return new RadarNetwork.Resource(png,"image/png",url);
            throw ApiError.invalid("Original article image unavailable.");
        }).when(network).get(anyString(),anyInt());
        execute();
        assertThat(sql.queryForObject("SELECT image_illustrative FROM news WHERE url LIKE '%/illustration'",Boolean.class)).isTrue();
        assertThat(sql.queryForObject("SELECT image_credit FROM news WHERE url LIKE '%/illustration'",String.class)).contains("Fixture Photographer","CC BY-SA 4.0","ilustrativa");
        assertThat(sql.queryForObject("SELECT image_source_url FROM news WHERE url LIKE '%/illustration'",String.class)).isEqualTo("https://commons.wikimedia.org/wiki/File:Fixture.png");
    }
    @Test @Order(10) void noRelevantNewsDoesNotInventArticlesAndPausedScheduleDoesNotCallProvider() throws Exception {
        reset(network);
        when(network.openai(anyString(),anyString())).thenAnswer(invocation->{var request=json.readTree((String)invocation.getArgument(1));Object output=request.has("tools")?List.of(Map.of("type","web_search_call","action",Map.of("sources",List.of())),Map.of("type","message","content",List.of(Map.of("type","output_text","text","Nenhuma novidade relevante encontrada.")))):List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text","{\"articles\":[]}"))));return json.writeValueAsString(Map.of("status","completed","output",output));});
        int before=sql.queryForObject("SELECT count(*) FROM news",Integer.class);execute();
        assertThat(sql.queryForObject("SELECT count(*) FROM news",Integer.class)).isEqualTo(before);assertThat(sql.queryForObject("SELECT message FROM radar_runs ORDER BY started_at DESC LIMIT 1",String.class)).contains("Nenhuma novidade");
        reset(network);radar.tick();verifyNoInteractions(network);
    }
    @Test @Order(11) void sourcePausedWhileSearchingCannotPublishAnArticle() throws Exception {
        provider("paused",false);call(put("/api/settings/radar"),root,settings(false,true,false),200);
        doAnswer(invocation->{
            var request=json.readTree((String)invocation.getArgument(1));
            if(request.has("tools"))return json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","web_search_call","action",Map.of("sources",List.of(Map.of("url","https://news.example.test/paused")))),Map.of("type","message","content",List.of(Map.of("type","output_text","text","Relatório válido."))))));
            sql.update("UPDATE radar_sources SET active=false WHERE id='fixture-source'");
            return json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(Map.of("articles",List.of(article("paused"))))))))));
        }).when(network).openai(anyString(),anyString());
        execute();assertThat(sql.queryForObject("SELECT count(*) FROM news WHERE url LIKE '%/paused'",Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT skipped FROM radar_runs ORDER BY started_at DESC LIMIT 1",Integer.class)).isEqualTo(1);
    }

}
