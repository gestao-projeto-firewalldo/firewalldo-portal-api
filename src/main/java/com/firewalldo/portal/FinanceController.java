package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.firewalldo.portal.SessionService.Actor;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Módulo Financeiro (V1.9): controle de investimentos operacionais, Pix e boletos.
 * Não executa pagamentos, não conecta bancos e não guarda credenciais financeiras.
 * Acesso: administradores e usuários com members.finance_access = true (conferido em todos os endpoints).
 */
@RestController
@RequestMapping("/api/finance")
class FinanceController {
    static final long FINANCE_LOCK = 470010L;
    private static final String[] CATEGORIES = {"infraestrutura", "ferramentas", "servicos", "publicidade", "equipamentos", "pessoal", "impostos", "outros"};
    private static final String[] FREQUENCIES = {"mensal", "trimestral", "semestral", "anual"};
    private static final String[] PIX_TYPES = {"cpf", "cnpj", "email", "telefone", "aleatoria"};
    private static final String[] FILE_TYPES = {"application/pdf", "image/png", "image/jpeg", "image/webp"};
    private static final int MAX_FILE = 4 * 1024 * 1024;
    private static final String TODAY = "(now() AT TIME ZONE 'America/Sao_Paulo')::date";
    private static final String MONTHS = "CASE i.frequency WHEN 'mensal' THEN 1 WHEN 'trimestral' THEN 3 WHEN 'semestral' THEN 6 WHEN 'anual' THEN 12 END";

    private static final String INVESTMENT = """
        SELECT jsonb_build_object('id', i.id, 'name', i.name, 'supplierName', i.supplier_name, 'legalName', i.legal_name,
          'document', i.document, 'category', i.category, 'description', i.description, 'ownerId', i.owner_id,
          'amountCents', i.amount_cents, 'billing', i.billing, 'frequency', i.frequency, 'startDate', i.start_date,
          'dueDay', i.due_day, 'endDate', i.end_date, 'status', i.status, 'paymentMethod', i.payment_method,
          'pixKeyType', i.pix_key_type, 'pixKey', i.pix_key, 'notes', i.notes, 'createdAt', i.created_at, 'updatedAt', i.updated_at,
          'monthlyCents', CASE WHEN i.billing = 'recorrente' THEN round(i.amount_cents::numeric / (%s))::bigint ELSE NULL END,
          'paidCents', COALESCE((SELECT sum(amount_cents) FROM finance_payments WHERE investment_id = i.id AND status = 'pago'), 0),
          'pendingCount', (SELECT count(*) FROM finance_payments WHERE investment_id = i.id AND status = 'pendente'))
        FROM finance_investments i
        """.formatted(MONTHS);

    private static final String PAYMENT = """
        SELECT jsonb_build_object('id', p.id, 'kind', p.kind, 'investmentId', p.investment_id, 'investmentName', i.name,
          'period', p.period, 'beneficiary', p.beneficiary, 'document', p.document, 'description', p.description,
          'amountCents', p.amount_cents, 'dueDate', p.due_date, 'paidAt', p.paid_at, 'storedStatus', p.status,
          'status', CASE WHEN p.status = 'pendente' AND p.due_date < %s THEN 'vencido' ELSE p.status END,
          'pixKeyType', p.pix_key_type, 'pixKey', p.pix_key, 'barcode', p.barcode,
          'hasAttachment', p.attachment IS NOT NULL, 'attachmentName', p.attachment_name,
          'hasReceipt', p.receipt IS NOT NULL, 'receiptName', p.receipt_name, 'createdAt', p.created_at)
        FROM finance_payments p LEFT JOIN finance_investments i ON i.id = p.investment_id
        """.formatted(TODAY);

    private final Store db;
    FinanceController(Store db) { this.db = db; }

    /* ======================= Resumo ======================= */

    @GetMapping("/summary")
    @Transactional
    Map<String, Object> summary(@RequestAttribute Actor actor) {
        actor.requireFinance(db);
        ensureOccurrences();
        var totals = db.first("""
            SELECT jsonb_build_object(
              'monthlyCents', COALESCE((SELECT sum(round(i.amount_cents::numeric / (%s))) FROM finance_investments i
                                        WHERE i.status = 'ativo' AND i.billing = 'recorrente'), 0),
              'activeCount', (SELECT count(*) FROM finance_investments WHERE status = 'ativo'),
              'paidCents', COALESCE((SELECT sum(amount_cents) FROM finance_payments WHERE status = 'pago'
                                     AND date_trunc('month', paid_at) = date_trunc('month', %s)), 0),
              'pendingCents', COALESCE((SELECT sum(amount_cents) FROM finance_payments WHERE status = 'pendente' AND due_date >= %s), 0),
              'overdueCents', COALESCE((SELECT sum(amount_cents) FROM finance_payments WHERE status = 'pendente' AND due_date < %s), 0),
              'overdueCount', (SELECT count(*) FROM finance_payments WHERE status = 'pendente' AND due_date < %s),
              'period', to_char(%s, 'YYYY-MM'))
            """.formatted(MONTHS, TODAY, TODAY, TODAY, TODAY, TODAY));
        Map<String, Object> result = new LinkedHashMap<>();
        totals.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
        result.put("upcoming", db.list(PAYMENT + " WHERE p.status = 'pendente' AND p.due_date >= " + TODAY + " ORDER BY p.due_date, p.id LIMIT 6"));
        return result;
    }

    /* ======================= Investimentos ======================= */

    @GetMapping("/investments")
    @Transactional
    List<JsonNode> investments(@RequestAttribute Actor actor) {
        actor.requireFinance(db);
        ensureOccurrences();
        return db.list(INVESTMENT + " ORDER BY CASE i.status WHEN 'ativo' THEN 0 WHEN 'pausado' THEN 1 ELSE 2 END, i.name, i.id");
    }

    @GetMapping("/investments/{id}/payments")
    @Transactional
    List<JsonNode> investmentPayments(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireFinance(db);
        return db.list(PAYMENT + " WHERE p.investment_id = ? ORDER BY p.due_date DESC, p.id", Input.id(id));
    }

    @PostMapping("/investments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode createInvestment(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireFinance(db);
        db.lock(FINANCE_LOCK);
        var data = investmentData(new Input(body));
        UUID id = UUID.randomUUID();
        db.sql.update("""
            INSERT INTO finance_investments (id, name, supplier_name, legal_name, document, category, description, owner_id, amount_cents,
              billing, frequency, start_date, due_day, end_date, status, payment_method, pix_key_type, pix_key, notes, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, data.name, data.supplier, data.legal, data.document, data.category, data.description, data.owner, data.amount,
            data.billing, data.frequency, data.start, data.dueDay, data.end, data.status, data.method, data.pixType, data.pixKey, data.notes, actor.id());
        ensureOccurrences();
        return db.first(INVESTMENT + " WHERE i.id = ?", id);
    }

    @PatchMapping("/investments/{id}")
    @Transactional
    JsonNode updateInvestment(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        actor.requireFinance(db);
        db.lock(FINANCE_LOCK);
        UUID investmentId = Input.id(id);
        if (db.first("SELECT to_jsonb(id) FROM finance_investments WHERE id = ? FOR UPDATE", investmentId) == null) throw ApiError.missing("Investimento não encontrado.");
        var data = investmentData(new Input(body));
        db.sql.update("""
            UPDATE finance_investments SET name = ?, supplier_name = ?, legal_name = ?, document = ?, category = ?, description = ?,
              owner_id = ?, amount_cents = ?, billing = ?, frequency = ?, start_date = ?, due_day = ?, end_date = ?, status = ?,
              payment_method = ?, pix_key_type = ?, pix_key = ?, notes = ?, updated_at = now() WHERE id = ?
            """, data.name, data.supplier, data.legal, data.document, data.category, data.description, data.owner, data.amount,
            data.billing, data.frequency, data.start, data.dueDay, data.end, data.status, data.method, data.pixType, data.pixKey, data.notes, investmentId);
        // Ocorrências pendentes ainda não vencidas acompanham o cadastro. Histórico pago/cancelado/vencido não muda.
        db.sql.update("""
            UPDATE finance_payments SET amount_cents = ?, beneficiary = ?, document = ?, kind = ?, pix_key_type = ?, pix_key = ?, updated_at = now()
            WHERE investment_id = ? AND status = 'pendente' AND due_date >= """ + TODAY,
            data.amount, beneficiary(data), data.document, data.method, data.method.equals("pix") ? data.pixType : null,
            data.method.equals("pix") ? data.pixKey : "", investmentId);
        if (data.status.equals("encerrado")) {
            // Encerrado: ocorrências futuras ainda não pagas deixam de ser cobradas. Pausado só interrompe novas gerações.
            db.sql.update("UPDATE finance_payments SET status = 'cancelado', updated_at = now() WHERE investment_id = ? AND status = 'pendente' AND due_date > " + TODAY, investmentId);
        }
        ensureOccurrences();
        return db.first(INVESTMENT + " WHERE i.id = ?", investmentId);
    }

    @DeleteMapping("/investments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteInvestment(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireFinance(db);
        db.lock(FINANCE_LOCK);
        UUID investmentId = Input.id(id);
        // Pendências futuras geradas automaticamente saem junto; pagamentos registrados ficam no histórico (investment_id = NULL).
        db.sql.update("DELETE FROM finance_payments WHERE investment_id = ? AND status = 'pendente' AND due_date >= " + TODAY
            + " AND attachment IS NULL AND receipt IS NULL AND barcode = ''", investmentId);
        if (db.sql.update("DELETE FROM finance_investments WHERE id = ?", investmentId) == 0) throw ApiError.missing("Investimento não encontrado.");
    }

    /* ======================= Pix e boletos ======================= */

    @GetMapping("/payments")
    @Transactional
    List<JsonNode> payments(@RequestAttribute Actor actor, @RequestParam String kind) {
        actor.requireFinance(db);
        if (!kind.equals("pix") && !kind.equals("boleto")) throw ApiError.invalid("Tipo inválido.");
        ensureOccurrences();
        return db.list(PAYMENT + " WHERE p.kind = ? ORDER BY CASE WHEN p.status = 'pendente' THEN 0 ELSE 1 END, p.due_date DESC, p.id", kind);
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode createPayment(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireFinance(db);
        db.lock(FINANCE_LOCK);
        var form = new Input(body);
        var data = paymentData(form, form.choice("kind", "pix", "boleto"));
        UUID id = UUID.randomUUID();
        db.sql.update("""
            INSERT INTO finance_payments (id, kind, investment_id, beneficiary, document, description, amount_cents, due_date,
              pix_key_type, pix_key, barcode, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, data.kind, data.investment, data.beneficiary, data.document, data.description, data.amount, data.due,
            data.pixType, data.pixKey, data.barcode, actor.id());
        saveFiles(form, id);
        return db.first(PAYMENT + " WHERE p.id = ?", id);
    }

    @PatchMapping("/payments/{id}")
    @Transactional
    JsonNode updatePayment(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        actor.requireFinance(db);
        db.lock(FINANCE_LOCK);
        UUID paymentId = Input.id(id);
        var current = db.first("SELECT jsonb_build_object('kind', kind, 'status', status) FROM finance_payments WHERE id = ? FOR UPDATE", paymentId);
        if (current == null) throw ApiError.missing("Pagamento não encontrado.");
        var form = new Input(body);
        var data = paymentData(form, current.path("kind").asText());
        String status = form.has("status") ? form.choice("status", "pendente", "pago", "cancelado") : current.path("status").asText();
        LocalDate paidAt = status.equals("pago") ? (form.has("paidAt") && !body.path("paidAt").isNull() ? form.date("paidAt") : Input.today()) : null;
        if (paidAt != null && paidAt.isAfter(Input.today())) throw ApiError.invalid("A data do pagamento não pode estar no futuro.");
        db.sql.update("""
            UPDATE finance_payments SET investment_id = ?, beneficiary = ?, document = ?, description = ?, amount_cents = ?, due_date = ?,
              pix_key_type = ?, pix_key = ?, barcode = ?, status = ?, paid_at = ?, updated_at = now() WHERE id = ?
            """, data.investment, data.beneficiary, data.document, data.description, data.amount, data.due,
            data.pixType, data.pixKey, data.barcode, status, paidAt, paymentId);
        saveFiles(form, paymentId);
        return db.first(PAYMENT + " WHERE p.id = ?", paymentId);
    }

    /** Marca como pago manualmente (após confirmação do usuário na interface). */
    @PostMapping("/payments/{id}/pay")
    @Transactional
    JsonNode pay(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        actor.requireFinance(db);
        db.lock(FINANCE_LOCK);
        UUID paymentId = Input.id(id);
        var form = new Input(body);
        form.only("paidAt", "receipt", "receiptName");
        LocalDate paidAt = form.has("paidAt") && !body.path("paidAt").isNull() ? form.date("paidAt") : Input.today();
        if (paidAt.isAfter(Input.today())) throw ApiError.invalid("A data do pagamento não pode estar no futuro.");
        if (db.sql.update("UPDATE finance_payments SET status = 'pago', paid_at = ?, updated_at = now() WHERE id = ? AND status <> 'cancelado'", paidAt, paymentId) == 0) {
            throw ApiError.conflict("Pagamento não encontrado ou cancelado.");
        }
        saveFiles(form, paymentId);
        return db.first(PAYMENT + " WHERE p.id = ?", paymentId);
    }

    @DeleteMapping("/payments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deletePayment(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireFinance(db);
        db.lock(FINANCE_LOCK);
        UUID paymentId = Input.id(id);
        var row = db.first("SELECT jsonb_build_object('investment', investment_id, 'period', period) FROM finance_payments WHERE id = ?", paymentId);
        if (row == null) throw ApiError.missing("Pagamento não encontrado.");
        if (!row.path("investment").isNull() && !row.path("period").isNull()) {
            // Ocorrência de cobrança recorrente: excluir faria o sistema gerá-la de novo. Ela é cancelada.
            db.sql.update("UPDATE finance_payments SET status = 'cancelado', paid_at = NULL, updated_at = now() WHERE id = ?", paymentId);
            return;
        }
        db.sql.update("DELETE FROM finance_payments WHERE id = ?", paymentId);
    }

    @GetMapping("/payments/{id}/attachment")
    ResponseEntity<byte[]> attachment(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireFinance(db);
        return file(id, "attachment", "attachment_type", "attachment_name");
    }

    @GetMapping("/payments/{id}/receipt")
    ResponseEntity<byte[]> receipt(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireFinance(db);
        return file(id, "receipt", "receipt_type", "receipt_name");
    }

    /* ======================= Ocorrências recorrentes ======================= */

    @Scheduled(initialDelayString = "${FINANCE_INITIAL_DELAY_MS:90000}", fixedDelayString = "${FINANCE_INTERVAL_MS:21600000}")
    @Transactional
    public void scheduled() { ensureOccurrences(); }

    /**
     * Gera as cobranças de cada investimento ativo até o mês seguinte ao atual.
     * Idempotente: o índice único (investment_id, period) impede duplicatas após reinícios ou atualizações.
     * Começa no mês do cadastro (ou da data de início, se posterior) para não inventar dívidas antigas.
     */
    public void ensureOccurrences() {
        db.lock(FINANCE_LOCK);
        YearMonth limit = YearMonth.now(ZoneId.of("America/Sao_Paulo")).plusMonths(1);
        var investments = db.list("""
            SELECT jsonb_build_object('id', id, 'billing', billing, 'frequency', frequency, 'start', start_date, 'dueDay', due_day,
              'end', end_date, 'created', (created_at AT TIME ZONE 'America/Sao_Paulo')::date, 'amount', amount_cents,
              'beneficiary', CASE WHEN supplier_name <> '' THEN supplier_name ELSE name END, 'document', document,
              'method', payment_method, 'pixType', pix_key_type, 'pixKey', pix_key, 'name', name)
            FROM finance_investments WHERE status = 'ativo'
            """);
        for (var inv : investments) {
            LocalDate start = LocalDate.parse(inv.path("start").asText());
            UUID id = Input.id(inv.path("id").asText());
            List<YearMonth> periods = new ArrayList<>();
            if (inv.path("billing").asText().equals("unica")) periods.add(YearMonth.from(start));
            else {
                int step = switch (inv.path("frequency").asText()) { case "trimestral" -> 3; case "semestral" -> 6; case "anual" -> 12; default -> 1; };
                YearMonth first = YearMonth.from(start);
                YearMonth floor = YearMonth.from(LocalDate.parse(inv.path("created").asText()));
                YearMonth end = inv.path("end").isNull() ? limit : YearMonth.from(LocalDate.parse(inv.path("end").asText()));
                for (YearMonth p = first; !p.isAfter(limit) && !p.isAfter(end) && periods.size() < 400; p = p.plusMonths(step)) {
                    if (!p.isBefore(floor)) periods.add(p);
                }
            }
            int day = inv.path("dueDay").isNull() ? start.getDayOfMonth() : inv.path("dueDay").asInt();
            boolean pix = inv.path("method").asText().equals("pix");
            for (YearMonth period : periods) {
                LocalDate due = inv.path("billing").asText().equals("unica") ? start : period.atDay(Math.min(day, period.lengthOfMonth()));
                db.sql.update("""
                    INSERT INTO finance_payments (id, kind, investment_id, period, beneficiary, document, description, amount_cents, due_date, pix_key_type, pix_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (investment_id, period) WHERE investment_id IS NOT NULL AND period IS NOT NULL DO NOTHING
                    """, UUID.randomUUID(), inv.path("method").asText(), id, period.toString(), inv.path("beneficiary").asText(),
                    inv.path("document").asText(), inv.path("name").asText() + " · " + period, inv.path("amount").asLong(), due,
                    pix ? (inv.path("pixType").isNull() ? null : inv.path("pixType").asText()) : null, pix ? inv.path("pixKey").asText() : "");
            }
        }
    }

    /* ======================= Validação ======================= */

    private record InvestmentData(String name, String supplier, String legal, String document, String category, String description,
                                  UUID owner, long amount, String billing, String frequency, LocalDate start, Integer dueDay, LocalDate end,
                                  String status, String method, String pixType, String pixKey, String notes) {}

    private InvestmentData investmentData(Input form) {
        form.only("name", "supplierName", "legalName", "document", "category", "description", "ownerId", "amountCents", "billing",
            "frequency", "startDate", "dueDay", "endDate", "status", "paymentMethod", "pixKeyType", "pixKey", "notes");
        String billing = form.choice("billing", "unica", "recorrente");
        String frequency = billing.equals("recorrente") ? form.choice("frequency", FREQUENCIES) : null;
        LocalDate start = form.date("startDate");
        LocalDate end = form.has("endDate") ? form.optionalDate("endDate") : null;
        if (end != null && end.isBefore(start)) throw ApiError.invalid("A data de término deve ser igual ou posterior à data de início.");
        Integer dueDay = billing.equals("recorrente") && form.has("dueDay") && !form.value().path("dueDay").isNull() ? form.number("dueDay", 1, 31) : null;
        UUID owner = form.has("ownerId") ? form.optionalId("ownerId") : null;
        if (owner != null) db.validateMembers(List.of(owner));
        String method = form.choice("paymentMethod", "pix", "boleto");
        String pixType = null, pixKey = "";
        if (method.equals("pix") && form.has("pixKey") && !form.optionalText("pixKey", 140).isEmpty()) {
            pixType = form.choice("pixKeyType", PIX_TYPES);
            pixKey = pixKey(pixType, form.text("pixKey", 1, 140));
        }
        return new InvestmentData(form.text("name", 2, 150), form.optionalText("supplierName", 150), form.optionalText("legalName", 200),
            document(form.optionalText("document", 30)), form.choice("category", CATEGORIES), form.optionalText("description", 2000), owner,
            form.cents("amountCents"), billing, frequency, start, dueDay, end,
            form.has("status") ? form.choice("status", "ativo", "pausado", "encerrado") : "ativo", method, pixType, pixKey,
            form.optionalText("notes", 2000));
    }

    private static String beneficiary(InvestmentData data) { return data.supplier.isEmpty() ? data.name : data.supplier; }

    private record PaymentData(String kind, UUID investment, String beneficiary, String document, String description, long amount,
                               LocalDate due, String pixType, String pixKey, String barcode) {}

    private PaymentData paymentData(Input form, String kind) {
        form.only("kind", "investmentId", "beneficiary", "document", "description", "amountCents", "dueDate", "pixKeyType", "pixKey",
            "barcode", "status", "paidAt", "attachment", "attachmentName", "receipt", "receiptName");
        UUID investment = form.has("investmentId") ? form.optionalId("investmentId") : null;
        if (investment != null && db.first("SELECT to_jsonb(id) FROM finance_investments WHERE id = ?", investment) == null) {
            throw ApiError.invalid("Investimento relacionado não encontrado.");
        }
        String pixType = null, pixKey = "", barcode = "";
        if (kind.equals("pix")) {
            pixType = form.choice("pixKeyType", PIX_TYPES);
            pixKey = pixKey(pixType, form.text("pixKey", 1, 140));
        } else {
            barcode = barcode(form.optionalText("barcode", 80));
        }
        return new PaymentData(kind, investment, form.text("beneficiary", 2, 150), document(form.optionalText("document", 30)),
            form.optionalText("description", 2000), form.cents("amountCents"), form.date("dueDate"), pixType, pixKey, barcode);
    }

    private void saveFiles(Input form, UUID id) {
        if (form.has("attachment")) {
            if (form.value().path("attachment").isNull() || form.value().path("attachment").asText().isEmpty()) {
                db.sql.update("UPDATE finance_payments SET attachment = NULL, attachment_type = NULL, attachment_name = '' WHERE id = ?", id);
            } else {
                var file = form.upload("attachment", MAX_FILE, FILE_TYPES);
                db.sql.update("UPDATE finance_payments SET attachment = ?, attachment_type = ?, attachment_name = ? WHERE id = ?",
                    file.bytes(), file.type(), fileName(form, "attachmentName", "boleto"), id);
            }
        }
        if (form.has("receipt")) {
            if (form.value().path("receipt").isNull() || form.value().path("receipt").asText().isEmpty()) {
                db.sql.update("UPDATE finance_payments SET receipt = NULL, receipt_type = NULL, receipt_name = '' WHERE id = ?", id);
            } else {
                var file = form.upload("receipt", MAX_FILE, FILE_TYPES);
                db.sql.update("UPDATE finance_payments SET receipt = ?, receipt_type = ?, receipt_name = ? WHERE id = ?",
                    file.bytes(), file.type(), fileName(form, "receiptName", "comprovante"), id);
            }
        }
    }

    private static String fileName(Input form, String field, String fallback) {
        String name = form.optionalText(field, 200).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return name.isBlank() ? fallback : name;
    }

    private ResponseEntity<byte[]> file(String id, String column, String typeColumn, String nameColumn) {
        var rows = db.sql.query("SELECT " + column + ", " + typeColumn + ", " + nameColumn + " FROM finance_payments WHERE id = ? AND " + column + " IS NOT NULL",
            (row, index) -> {
                String name = row.getString(3).isBlank() ? "arquivo" : row.getString(3);
                String ascii = name.replaceAll("[^A-Za-z0-9._-]", "_");
                return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, row.getString(2))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + ascii + "\"; filename*=UTF-8''" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
                    .header("X-Content-Type-Options", "nosniff")
                    .body(row.getBytes(1));
            }, Input.id(id));
        if (rows.isEmpty()) throw ApiError.missing("Arquivo não encontrado.");
        return rows.get(0);
    }

    /** CPF (11) ou CNPJ (14) com dígitos verificadores. Vazio é permitido. */
    static String document(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) return "";
        if (digits.length() == 11 && validCpf(digits)) return digits;
        if (digits.length() == 14 && validCnpj(digits)) return digits;
        throw ApiError.invalid("CPF/CNPJ inválido. Confira os números.");
    }

    static String pixKey(String type, String raw) {
        String key = raw.strip();
        switch (type) {
            case "cpf" -> { String d = key.replaceAll("\\D", ""); if (d.length() == 11 && validCpf(d)) return d; throw ApiError.invalid("Chave Pix inválida: CPF incorreto."); }
            case "cnpj" -> { String d = key.replaceAll("\\D", ""); if (d.length() == 14 && validCnpj(d)) return d; throw ApiError.invalid("Chave Pix inválida: CNPJ incorreto."); }
            case "email" -> { if (key.length() <= 77 && key.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) return key.toLowerCase(java.util.Locale.ROOT); throw ApiError.invalid("Chave Pix inválida: e-mail incorreto."); }
            case "telefone" -> {
                String d = key.replaceAll("\\D", "");
                if (d.startsWith("55") && (d.length() == 12 || d.length() == 13)) d = d.substring(2);
                if (d.length() == 10 || d.length() == 11) return "+55" + d;
                throw ApiError.invalid("Chave Pix inválida: informe o telefone com DDD.");
            }
            default -> {
                String k = key.toLowerCase(java.util.Locale.ROOT);
                if (k.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return k;
                throw ApiError.invalid("Chave Pix inválida: a chave aleatória tem 32 caracteres no formato 8-4-4-4-12.");
            }
        }
    }

    static String barcode(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty() || digits.length() == 47 || digits.length() == 48) return digits;
        throw ApiError.invalid("Linha digitável inválida: ela deve ter 47 números (boleto bancário) ou 48 (contas de consumo).");
    }

    static boolean validCpf(String d) {
        if (d.chars().distinct().count() == 1) return false;
        for (int t = 9; t < 11; t++) {
            int sum = 0;
            for (int i = 0; i < t; i++) sum += (d.charAt(i) - '0') * (t + 1 - i);
            int check = (sum * 10) % 11 % 10;
            if (check != d.charAt(t) - '0') return false;
        }
        return true;
    }

    static boolean validCnpj(String d) {
        if (d.chars().distinct().count() == 1) return false;
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}, w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int s1 = 0, s2 = 0;
        for (int i = 0; i < 12; i++) s1 += (d.charAt(i) - '0') * w1[i];
        int c1 = s1 % 11 < 2 ? 0 : 11 - s1 % 11;
        for (int i = 0; i < 13; i++) s2 += (i == 12 ? c1 : d.charAt(i) - '0') * w2[i];
        int c2 = s2 % 11 < 2 ? 0 : 11 - s2 % 11;
        return c1 == d.charAt(12) - '0' && c2 == d.charAt(13) - '0';
    }
}
