package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.time.YearMonth;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Cenários obrigatórios da V1.9: Radar manual, ideias, fotos, notificações e Financeiro. */
@SpringBootTest(properties = {"SESSION_SECURE=false", "ADMIN_EMAIL=v19@example.test", "ADMIN_PASSWORD=v19-test-password-ok", "FINANCE_INITIAL_DELAY_MS=3600000"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PortalV19IntegrationTest {
    static final EmbeddedPostgres PG;
    static {
        try { PG = EmbeddedPostgres.builder().setServerConfig("unix_socket_directories", "").setPort(0).start(); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
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
    @Autowired FinanceController finance;
    static Cookie root, user;
    static String rootId, userId, png, pdf;

    MvcResult call(MockHttpServletRequestBuilder req, Cookie cookie, Object body, int expected) throws Exception {
        req.contentType("application/json");
        if (cookie != null) req.cookie(cookie);
        if (body != null) req.content(json.writeValueAsBytes(body));
        var result = mvc.perform(req).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
        return result;
    }
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    Cookie login(String email, String password) throws Exception {
        return call(post("/api/auth/login"), null, Map.of("email", email, "senha", password), 200).getResponse().getCookie("fw_session");
    }
    Map<String, Object> news(String title, String slot, boolean image) {
        var item = new HashMap<String, Object>(Map.of("title", title, "summary", "Resumo curto e objetivo da notícia.", "body",
            "Primeiro parágrafo com o texto completo.\n\nSegundo parágrafo da notícia.", "slot", slot, "sourceName", "CERT.br",
            "sourceUrl", "https://www.cert.br/"));
        if (image) item.put("image", png);
        return item;
    }

    @Test @Order(1) void setup() throws Exception {
        root = login("v19@example.test", "v19-test-password-ok");
        rootId = body(call(get("/api/auth/me"), root, null, 200)).path("id").asText();
        userId = body(call(post("/api/users"), root, Map.of("name", "Tiago Diniz", "email", "tiago@example.test", "role", "usuario", "title", "", "senha", "tiago-test-password"), 201)).path("id").asText();
        user = login("tiago@example.test", "tiago-test-password");
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        png = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        pdf = "data:application/pdf;base64," + Base64.getEncoder().encodeToString("%PDF-1.4\n%%EOF".getBytes());
    }

    @Test @Order(2) void radarRespectsSlotLimitsInBackend() throws Exception {
        call(post("/api/news"), user, news("Sem permissão", "geral", false), 403);
        call(post("/api/news"), root, news("Principal sem imagem", "principal", false), 400);
        String principal = body(call(post("/api/news"), root, news("Notícia principal", "principal", true), 201)).path("id").asText();
        var conflict = body(call(post("/api/news"), root, news("Outra principal", "principal", true), 409));
        assertThat(conflict.path("message").asText()).isEqualTo("Já existe uma notícia principal cadastrada. Exclua ou altere a categoria da notícia atual para liberar esta posição.");
        for (int i = 1; i <= 3; i++) call(post("/api/news"), root, news("Secundária " + i, "secundaria", true), 201);
        call(post("/api/news"), root, news("Quarta secundária", "secundaria", true), 409);
        String general = null;
        for (int i = 1; i <= 5; i++) general = body(call(post("/api/news"), root, news("Geral " + i, "geral", false), 201)).path("id").asText();
        call(post("/api/news"), root, news("Décima notícia", "geral", false), 409);
        var list = body(call(get("/api/news"), user, null, 200));
        assertThat(list).hasSize(9);
        assertThat(list.get(0).path("slot").asText()).isEqualTo("principal");
        assertThat(list.get(0).path("imageUrl").asText()).startsWith("/api/news/" + principal + "/image");
        mvc.perform(get(list.get(0).path("imageUrl").asText()).cookie(user)).andExpect(r -> assertThat(r.getResponse().getContentType()).isEqualTo("image/png"));

        // Geral sem imagem não pode ser promovida; principal não pode ir para secundária lotada.
        call(patch("/api/news/" + general), root, Map.of("slot", "secundaria"), 409);
        call(patch("/api/news/" + principal), root, Map.of("slot", "secundaria"), 409);
        // Excluir libera a posição, e a principal pode ser reclassificada.
        String secondary = list.get(1).path("id").asText();
        call(delete("/api/news/" + secondary), user, null, 403);
        call(delete("/api/news/" + secondary), root, null, 204);
        var moved = body(call(patch("/api/news/" + principal), root, Map.of("slot", "secundaria", "title", "Agora secundária"), 200));
        assertThat(moved.path("slot").asText()).isEqualTo("secundaria");
        call(patch("/api/news/" + general), root, Map.of("slot", "principal"), 400);
        var promoted = body(call(patch("/api/news/" + general), root, Map.of("slot", "principal", "image", png), 200));
        assertThat(promoted.path("featured").asBoolean()).isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM news WHERE slot='principal'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> sql.update("UPDATE news SET slot='principal' WHERE id=?", UUID.fromString(principal))).isNotNull();
    }

    @Test @Order(3) void ideasAreReallyDeleted() throws Exception {
        String id = body(call(post("/api/ideas"), user, Map.of("title", "Vídeo sobre firewall", "description", "Roteiro", "category", "video"), 201)).path("id").asText();
        call(post("/api/ideas/" + id + "/vote"), root, Map.of(), 204);
        call(delete("/api/ideas/" + id), user, null, 403);
        call(delete("/api/ideas/" + id), root, null, 204);
        assertThat(sql.queryForObject("SELECT count(*) FROM ideas WHERE id=?", Integer.class, UUID.fromString(id))).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM idea_votes WHERE idea_id=?", Integer.class, UUID.fromString(id))).isZero();
        call(delete("/api/ideas/" + id), root, null, 404);
    }

    @Test @Order(4) void profilePhotoIsVisibleToOthersAndFallsBackToInitials() throws Exception {
        call(put("/api/users/" + rootId + "/photo"), user, Map.of("image", png), 403);
        call(put("/api/users/" + userId + "/photo"), user, Map.of("image", "data:image/svg+xml;base64,PHN2Zz4="), 400);
        var me = body(call(put("/api/users/" + userId + "/photo"), user, Map.of("image", png), 200));
        String url = me.path("avatarUrl").asText();
        assertThat(url).startsWith("/api/users/" + userId + "/photo?v=");
        var seenByAdmin = body(call(get("/api/users"), root, null, 200));
        assertThat(seenByAdmin.findValuesAsText("avatarUrl")).contains(url);
        mvc.perform(get(url).cookie(root)).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        var removed = body(call(delete("/api/users/" + userId + "/photo"), user, null, 200));
        assertThat(removed.path("avatarUrl").isNull()).isTrue();
        assertThat(removed.path("initials").asText()).isEqualTo("TD");
    }

    @Test @Order(5) void notificationsReachTheRightPersonAndCanBeRead() throws Exception {
        var task = json.createObjectNode().put("title", "Criar thumbnail do vídeo").put("description", "").put("status", "a_fazer")
            .put("priority", "media").put("open", false).put("maxAssignees", 1).put("category", "design");
        task.putNull("projectId"); task.putNull("dueDate"); task.putArray("checklist"); task.putArray("assigneeIds").add(userId);
        String taskId = body(call(post("/api/tasks"), root, task, 201)).path("id").asText();
        var mine = body(call(get("/api/notifications"), user, null, 200));
        var assigned = mine.get(0);
        assertThat(assigned.path("title").asText()).isEqualTo("Nova tarefa atribuída");
        assertThat(assigned.path("description").asText()).isEqualTo("Você recebeu a tarefa \"Criar thumbnail do vídeo\".");
        assertThat(assigned.path("createdAtIso").asText()).isNotBlank();
        assertThat(body(call(get("/api/notifications"), root, null, 200)).findValuesAsText("description")).doesNotContain(assigned.path("description").asText());
        call(post("/api/notifications/" + assigned.path("id").asText() + "/read"), root, Map.of(), 404);
        call(post("/api/notifications/" + assigned.path("id").asText() + "/read"), user, Map.of(), 204);
        call(patch("/api/tasks/" + taskId + "/move"), root, Map.of("status", "em_andamento"), 204);
        assertThat(body(call(get("/api/notifications"), user, null, 200)).findValuesAsText("title")).contains("Tarefa atualizada");
    }

    ObjectNode investment(String name, String billing) {
        var item = json.createObjectNode().put("name", name).put("supplierName", "Railway").put("category", "infraestrutura")
            .put("amountCents", 9000).put("billing", billing).put("startDate", YearMonth.now().atDay(1).toString())
            .put("status", "ativo").put("paymentMethod", "pix").put("pixKeyType", "email").put("pixKey", "Financeiro@Example.test");
        if (billing.equals("recorrente")) item.put("frequency", "mensal").put("dueDay", 15);
        return item;
    }

    @Test @Order(6) void financeIsRestrictedAndRecurringOccurrencesAreNotDuplicated() throws Exception {
        call(get("/api/finance/summary"), user, null, 403);
        call(post("/api/finance/investments"), user, investment("Hospedagem", "recorrente"), 403);
        var bad = investment("Hospedagem", "recorrente"); bad.put("pixKeyType", "cpf").put("pixKey", "111.111.111-11");
        call(post("/api/finance/investments"), root, bad, 400);
        String id = body(call(post("/api/finance/investments"), root, investment("Hospedagem de servidor", "recorrente"), 201)).path("id").asText();
        var occurrences = body(call(get("/api/finance/investments/" + id + "/payments"), root, null, 200));
        assertThat(occurrences).hasSize(2); // mês atual + próximo
        assertThat(occurrences.findValuesAsText("pixKey")).containsOnly("financeiro@example.test");
        finance.ensureOccurrences();
        call(get("/api/finance/payments").param("kind", "pix"), root, null, 200);
        assertThat(sql.queryForObject("SELECT count(*) FROM finance_payments WHERE investment_id=?", Integer.class, UUID.fromString(id))).isEqualTo(2);

        // Acesso individual concedido por um administrador.
        call(patch("/api/users/" + userId), user, Map.of("financeAccess", true), 403);
        call(patch("/api/users/" + userId), root, Map.of("financeAccess", true), 200);
        var summary = body(call(get("/api/finance/summary"), user, null, 200));
        assertThat(summary.path("monthlyCents").asLong()).isEqualTo(9000);

        String paymentId = occurrences.get(occurrences.size() - 1).path("id").asText();
        var paid = body(call(post("/api/finance/payments/" + paymentId + "/pay"), user, Map.of("receipt", pdf, "receiptName", "comprovante.pdf"), 200));
        assertThat(paid.path("status").asText()).isEqualTo("pago");
        assertThat(paid.path("hasReceipt").asBoolean()).isTrue();
        mvc.perform(get("/api/finance/payments/" + paymentId + "/receipt").cookie(user)).andExpect(r -> assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf"));

        // Boleto avulso vencido é identificado sem ser marcado como pago.
        var boleto = Map.of("kind", "boleto", "beneficiary", "Gráfica", "amountCents", 15000, "dueDate", "2026-01-10",
            "barcode", "34191.79001 01043.510047 91020.150008 8 99990000015000");
        var created = body(call(post("/api/finance/payments"), user, boleto, 201));
        assertThat(created.path("status").asText()).isEqualTo("vencido");
        call(post("/api/finance/payments"), user, Map.of("kind", "boleto", "beneficiary", "X", "amountCents", 100, "dueDate", "2026-01-10", "barcode", "123"), 400);
        call(delete("/api/finance/payments/" + created.path("id").asText()), user, null, 204);

        // Excluir uma ocorrência recorrente a cancela (não volta a ser gerada).
        String first = occurrences.get(0).path("id").asText();
        call(delete("/api/finance/payments/" + first), root, null, 204);
        finance.ensureOccurrences();
        assertThat(sql.queryForObject("SELECT status FROM finance_payments WHERE id=?", String.class, UUID.fromString(first))).isEqualTo("cancelado");
        call(delete("/api/finance/investments/" + id), root, null, 204);
        assertThat(sql.queryForObject("SELECT count(*) FROM finance_payments WHERE id=?", Integer.class, UUID.fromString(paymentId))).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE entity='finance_payments' AND details::text LIKE '%PDF%'", Integer.class)).isZero();
        assertThat(FinanceController.validCnpj("11222333000181")).isTrue();
        assertThat(FinanceController.validCpf("52998224725")).isTrue();
        assertThat(List.of(FinanceController.pixKey("telefone", "(11) 98765-4321"))).containsExactly("+5511987654321");
    }
}
