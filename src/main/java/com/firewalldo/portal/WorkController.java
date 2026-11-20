package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firewalldo.portal.SessionService.Actor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class WorkController {
    private static final long TASK_LOCK = 470003;
    private static final String PROJECT = """
        SELECT jsonb_build_object('id', p.id, 'name', p.name, 'subtitle', p.subtitle,
          'category', p.category, 'brand', p.brand, 'description', p.description, 'icon', p.icon, 'color', p.color, 'status', p.status,
          'ownerIds', p.owner_ids, 'milestones', p.milestones, 'startDate', p.start_date, 'dueDate', p.due_date,
          'progress', COALESCE(round(100 * stats.done::numeric / NULLIF(stats.total, 0)), CASE WHEN p.status='concluido' THEN 100 ELSE COALESCE((SELECT round(100.0*count(*) FILTER(WHERE (m->>'done')::boolean)/NULLIF(count(*),0)) FROM jsonb_array_elements(p.milestones) m),0) END))
        FROM projects p CROSS JOIN LATERAL (
          SELECT count(*) AS total, count(*) FILTER (WHERE status = 'concluido') AS done FROM tasks WHERE project_id = p.id
        ) stats
        """;
    private static final String TASK = """
        SELECT jsonb_build_object('id', t.id, 'title', t.title, 'description', t.description, 'projectId', t.project_id,
          'category', t.category, 'status', t.status, 'priority', t.priority, 'assigneeIds', t.assignee_ids, 'open', t.open,
          'maxAssignees', t.max_assignees, 'eligibleAssigneeIds', t.eligible_assignee_ids, 'dueDate', t.due_date, 'checklist', t.checklist, 'createdBy', t.created_by,
          'createdAt', t.created_at, 'updatedAt', t.updated_at, 'order', t.position, 'ticketNumber',t.ticket_number,'scheduleId',t.schedule_id,'scheduledFor',t.scheduled_for,'scheduleIndividual',t.schedule_member_id IS NOT NULL,
          'finalizedAt',t.finalized_at,'finalizedBy',t.finalized_by,'finalizedByName',t.finalized_by_name,'finalizedProjectName',t.finalized_project_name)
        FROM tasks t
        """;
    private final Store db;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    WorkController(Store db, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @GetMapping("/projects")
    List<JsonNode> projects() { return db.list(PROJECT + " ORDER BY p.start_date DESC, p.name, p.id"); }

    @GetMapping("/projects/{id}")
    JsonNode project(@PathVariable String id) { return project(Input.id(id)); }

    private JsonNode project(UUID id) { return db.require("Projeto não encontrado.", PROJECT + " WHERE p.id = ?", id); }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode createProject(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        var form = new Input(body);
        var owners = form.ids("ownerIds");
        db.validateMembers(owners);
        String color = form.text("color", 7, 7);
        if (!color.matches("#[0-9a-fA-F]{6}")) throw ApiError.invalid("Cor inválida.");
        var milestones = json.createArrayNode();
        if (form.has("milestones")) {
            for (var milestone : Input.list(body.path("milestones"), 50, Input::new)) {
                milestones.addObject().put("id", UUID.randomUUID().toString())
                    .put("title", milestone.text("title", 1, 200)).put("dueDate", milestone.date("dueDate").toString()).put("done", false);
            }
        }
        UUID id = UUID.randomUUID();
        db.sql.update("""
            INSERT INTO projects (id, name, subtitle, description, icon, color, owner_ids, start_date, due_date, milestones, status, category, brand)
            VALUES (?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?::jsonb, ?, ?, ?)
            """, id, form.text("name", 3, 150), form.text("subtitle", 0, 250), form.text("description", 0, 8000),
            form.choice("icon", "code", "layout", "smartphone", "play", "globe", "palette", "shield", "server"),
            color, Store.array(owners), Input.today(), form.date("dueDate"), milestones.toString(), form.has("status")?form.choice("status","planejamento","em_andamento","pausado","concluido"):"planejamento", projectCategory(form), form.has("brand")?form.text("brand",0,140):"");
        var created=project(id);
        notifyProject(actor,created);
        return created;
    }

    @PatchMapping("/projects/{id}")
    @Transactional
    JsonNode updateProject(@RequestAttribute Actor actor,@PathVariable String id,@RequestBody JsonNode body) {
        actor.requireAdmin();
        UUID uuid=Input.id(id);
        new Input(body).only("name","subtitle","description","icon","color","status","ownerIds","dueDate","milestones","category","brand");
        db.require("Projeto não encontrado.","SELECT to_jsonb(id) FROM projects WHERE id=? FOR UPDATE",uuid);
        var before=project(uuid);
        var merged=((ObjectNode)before).deepCopy(); merged.setAll((ObjectNode)body);
        var form=new Input(merged);
        var owners=form.ids("ownerIds");db.validateMembers(owners);
        String color=form.text("color",7,7);if (!color.matches("#[0-9a-fA-F]{6}")) throw ApiError.invalid("Cor inválida.");
        var milestones=json.createArrayNode();
        for (var milestone:Input.list(merged.path("milestones"),50,Input::new)) {
            milestones.addObject().put("id",milestone.has("id")?milestone.key("id"):UUID.randomUUID().toString())
              .put("title",milestone.text("title",1,200)).put("dueDate",milestone.date("dueDate").toString()).put("done",milestone.bool("done"));
        }
        Input.unique(java.util.stream.StreamSupport.stream(milestones.spliterator(),false).map(m->m.path("id").asText()).toList());
        db.sql.update("""
          UPDATE projects SET name=?,subtitle=?,description=?,icon=?,color=?,status=?,owner_ids=?::uuid[],due_date=?,milestones=?::jsonb,category=?,brand=? WHERE id=?
          """,form.text("name",3,150),form.text("subtitle",0,250),form.text("description",0,8000),
          form.choice("icon","code","layout","smartphone","play","globe","palette","shield","server"),color,
          form.choice("status","planejamento","em_andamento","pausado","concluido"),Store.array(owners),form.date("dueDate"),milestones.toString(),projectCategory(form),form.text("brand",0,140),uuid);
        var updated=project(uuid);
        if (!updated.equals(before)) notifyProject(actor,updated,before);
        return updated;
    }

    private String projectCategory(Input form) { return !form.has("category") || form.value().path("category").isNull() ? null : form.choice("category", Input.AREAS); }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteProject(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireAdmin();
        db.lock(TASK_LOCK);
        UUID projectId = Input.id(id);
        db.require("Projeto não encontrado.", "SELECT to_jsonb(id) FROM projects WHERE id=? FOR UPDATE", projectId);
        // Keep tickets, occurrences and audit history; future releases also become unlinked.
        db.sql.update("UPDATE tasks SET project_id=NULL,updated_at=? WHERE project_id=?", Input.today(), projectId);
        db.sql.update("UPDATE task_schedules SET config=jsonb_set(config,'{projectId}','null'::jsonb),updated_at=clock_timestamp() WHERE config->>'projectId'=?", projectId.toString());
        db.sql.update("DELETE FROM projects WHERE id=?", projectId);
    }

    @PatchMapping("/projects/{id}/milestones/{milestoneId}")
    @Transactional
    JsonNode toggleMilestone(@RequestAttribute Actor actor, @PathVariable String id, @PathVariable String milestoneId) {
        actor.requireAdmin();
        UUID projectId = Input.id(id);
        var milestones = db.require("Projeto não encontrado.", "SELECT milestones FROM projects WHERE id = ? FOR UPDATE", projectId);
        for (var milestone : milestones) {
            if (milestone.path("id").asText().equals(milestoneId)) {
                ((ObjectNode) milestone).put("done", !milestone.path("done").asBoolean());
                db.sql.update("UPDATE projects SET milestones = ?::jsonb WHERE id = ?", milestones.toString(), projectId);
                var updated=project(projectId);
                notifyProject(actor,updated,updated);
                return updated;
            }
        }
        throw ApiError.missing("Marco não encontrado.");
    }

    @GetMapping("/tasks")
    List<JsonNode> tasks(@RequestAttribute Actor actor, @RequestParam(required = false) String projectId,
                         @RequestParam(required = false) String assigneeId,
                         @RequestParam(required = false) String status,
                         @RequestParam(defaultValue="false") boolean includeFinalized) {
        boolean availableBacklog = !actor.admin() && assigneeId == null;
        if(!actor.admin()) {
            if(assigneeId!=null && !Input.id(assigneeId).equals(actor.id()))throw ApiError.forbidden();
            assigneeId=actor.id().toString();
        }
        return db.list(TASK + """
            WHERE (?::uuid IS NULL OR t.project_id = ?::uuid)
              AND (?::uuid IS NULL OR ?::uuid = ANY(t.assignee_ids)
                OR (? AND t.status='backlog' AND t.open AND cardinality(t.assignee_ids)<t.max_assignees
                  AND (cardinality(t.eligible_assignee_ids)=0 OR ?::uuid=ANY(t.eligible_assignee_ids))))
              AND (?::text IS NULL OR t.status = ?) AND (? OR t.finalized_at IS NULL)
            ORDER BY t.position, t.id
            """, projectId == null ? null : Input.id(projectId), projectId, assigneeId == null ? null : Input.id(assigneeId),
            assigneeId, availableBacklog, actor.id(), status, status, includeFinalized);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    JsonNode createTask(@RequestAttribute Actor actor, @RequestBody JsonNode body) {
        actor.requireAdmin();
        db.lock(TASK_LOCK);
        var form = new Input(body);
        UUID id = UUID.randomUUID();
        var data = taskData(form);
        if (!List.of("backlog", "a_fazer").contains(data.status))
            throw ApiError.invalid("Crie a tarefa no Backlog ou em A fazer e use os botões para avançar.");
        var date = Input.today();
        db.sql.update("""
            INSERT INTO tasks (id, title, description, project_id, category, status, priority, assignee_ids, eligible_assignee_ids, open,
              max_assignees, due_date, checklist, created_by, created_at, updated_at, position)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?::uuid[], ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """, id, data.title, data.description, data.project, data.category, data.status, data.priority,
            Store.array(data.assignees), Store.array(data.eligible), data.open, data.max, data.due, data.checklist, actor.id(), date, date, endOf(data.status));
        notifyAssignees(actor.id(), data.title, data.assignees);
        if(data.open) db.sql.update("""
          INSERT INTO notifications(id,member_id,kind,title,description)
          SELECT gen_random_uuid(),m.id,'tarefa','Nova tarefa disponível',? FROM members m WHERE m.active AND m.id<>?
          AND NOT (m.id=ANY(?::uuid[])) AND (cardinality(?::uuid[])=0 OR m.id=ANY(?::uuid[]))
          AND COALESCE((SELECT (preferences#>>'{notifications,taskAssigned}')::boolean FROM member_preferences WHERE member_id=m.id),true)
          """,data.title,actor.id(),Store.array(data.assignees),Store.array(data.eligible),Store.array(data.eligible));
        return task(id);
    }

    @PatchMapping("/tasks/{id}")
    @Transactional
    JsonNode updateTask(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        UUID taskId = Input.id(id);
        db.lock(TASK_LOCK);
        var current = (ObjectNode) task(taskId);
        requireNotFinalized(current);
        checkAssignee(actor, current);
        var patch = new Input(body);
        String oldStatus = current.path("status").asText();
        String status = patch.has("status") ? patch.choice("status", Input.TASK_STATUS) : oldStatus;
        validateTransition(actor, current, status);
        int position = oldStatus.equals(status) ? current.path("order").asInt() : endOf(status);
        if (!actor.admin()) {
            if (hasRestrictedTaskFields(patch.value())) {
                throw ApiError.forbidden();
            }
            String checklist = patch.has("checklist") ? checklist(body.path("checklist")) : current.path("checklist").toString();
            db.sql.update("UPDATE tasks SET status = ?, position = ?, checklist = ?::jsonb, updated_at = ? WHERE id = ?",
                status, position, checklist, Input.today(), taskId);
            notifyTaskStatus(actor.id(), taskId, current.path("title").asText(), oldStatus, status);
        } else {
            patch.only("title", "description", "projectId", "category", "status", "priority", "assigneeIds",
                "open", "maxAssignees", "dueDate", "checklist", "eligibleAssigneeIds");
            List<UUID> oldAssignees = new Input(current).ids("assigneeIds");
            current.setAll((ObjectNode) body);
            var data = taskData(new Input(current), oldAssignees);
            if (oldStatus.equals("backlog") && !data.assignees.equals(oldAssignees))
                throw ApiError.invalid("No Backlog, selecione quem pode aceitar. Os responsáveis são definidos pelos aceites.");
            if(current.path("scheduleIndividual").asBoolean() && (!data.assignees.equals(oldAssignees) || !data.eligible.isEmpty() || data.open || data.max!=1))
                throw ApiError.invalid("A tarefa da Agenda é individual. Edite os responsáveis na Agenda para as próximas aberturas.");
            db.sql.update("""
                UPDATE tasks SET title = ?, description = ?, project_id = ?, category = ?, status = ?, priority = ?,
                  assignee_ids = ?::uuid[], eligible_assignee_ids = ?::uuid[], open = ?, max_assignees = ?, due_date = ?, checklist = ?::jsonb,
                  position = ?, updated_at = ? WHERE id = ?
                """, data.title, data.description, data.project, data.category, data.status, data.priority,
                Store.array(data.assignees), Store.array(data.eligible), data.open, data.max, data.due, data.checklist,
                oldStatus.equals(data.status) ? position : endOf(data.status), Input.today(), taskId);
            notifyAssignees(actor.id(), data.title, data.assignees.stream().filter(member -> !oldAssignees.contains(member)).toList());
            notifyTaskStatus(actor.id(), taskId, data.title, oldStatus, data.status);
        }
        return task(taskId);
    }

    @PatchMapping("/tasks/{id}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void move(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        UUID taskId = Input.id(id);
        db.lock(TASK_LOCK);
        var current = task(taskId);
        requireNotFinalized(current);
        if (!actor.general() && !new Input(current).ids("assigneeIds").contains(actor.id())) throw ApiError.forbidden();
        var form = new Input(body);
        form.only("status", "beforeId");
        String status = form.choice("status", Input.TASK_STATUS);
        validateTransition(actor, current, status);
        UUID beforeId = form.has("beforeId") ? form.optionalId("beforeId") : null;
        if (beforeId != null) throw ApiError.invalid("Use os botões Avançar e Voltar para mover a tarefa.");
        if (current.path("status").asText().equals(status)) return;
        db.sql.update("UPDATE tasks SET status = ?, position = ?, updated_at = ? WHERE id = ?", status, endOf(status), Input.today(), taskId);
        notifyTaskStatus(actor.id(), taskId, current.path("title").asText(), current.path("status").asText(), status);
    }

    @PostMapping("/tasks/{id}/accept")
    @Transactional
    JsonNode accept(@RequestAttribute Actor actor, @PathVariable String id) {
        UUID taskId = Input.id(id);
        db.lock(TASK_LOCK);
        var current = new Input(task(taskId));
        requireNotFinalized(current.value());
        var assignees = current.ids("assigneeIds");
        if (assignees.contains(actor.id())) return current.value();
        var eligible = current.ids("eligibleAssigneeIds");
        if (!eligible.isEmpty() && !eligible.contains(actor.id())) throw ApiError.forbidden();
        int limit = current.number("maxAssignees", 1, 100);
        if (!current.text("status",1,30).equals("backlog") || !current.bool("open") || assignees.size() >= limit) {
            throw new ApiError(409, "NO_SLOTS", "Não há vagas nesta tarefa.");
        }
        db.validateMembers(List.of(actor.id()));
        assignees.add(actor.id());
        boolean open = assignees.size() < limit;
        String status = current.text("status", 1, 30);
        boolean move = !open && status.equals("backlog");
        db.sql.update("UPDATE tasks SET assignee_ids = ?::uuid[], open = ?, status = ?, position = ?, updated_at = ? WHERE id = ?",
            Store.array(assignees), open, move ? "a_fazer" : status,
            move ? endOf("a_fazer") : current.number("order", 1, Integer.MAX_VALUE), Input.today(), taskId);
        if (move) notifyTaskStatus(actor.id(),taskId,current.text("title",3,200),status,"a_fazer");
        return task(taskId);
    }

    @PostMapping("/tasks/{id}/leave")
    @Transactional
    JsonNode leave(@RequestAttribute Actor actor, @PathVariable String id) {
        UUID taskId = Input.id(id);
        db.lock(TASK_LOCK);
        var current = new Input(task(taskId));
        requireNotFinalized(current.value());
        if(current.value().path("scheduleIndividual").asBoolean())throw ApiError.invalid("A atribuição desta tarefa individual é definida pela Agenda.");
        var assignees = current.ids("assigneeIds");
        if(!actor.admin()&&!assignees.contains(actor.id()))throw ApiError.forbidden();
        if (assignees.remove(actor.id())) {
            db.sql.update("UPDATE tasks SET assignee_ids = ?::uuid[], open = (status='backlog'), updated_at = ? WHERE id = ?",
                Store.array(assignees), Input.today(), taskId);
        }
        return task(taskId);
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@RequestAttribute Actor actor, @PathVariable String id) {
        actor.requireAdmin();
        db.lock(TASK_LOCK);
        requireNotFinalized(task(Input.id(id)));
        if (db.sql.update("DELETE FROM tasks WHERE id = ?", Input.id(id)) == 0) throw ApiError.missing("Tarefa não encontrada.");
    }

    @PostMapping("/tasks/{id}/finalize")
    @Transactional
    JsonNode finalizeTask(@RequestAttribute Actor actor,@PathVariable String id) {
        if(!actor.general())throw ApiError.forbidden();
        db.lock(TASK_LOCK);
        UUID taskId=Input.id(id);var current=task(taskId);
        if(!current.path("finalizedAt").isNull())return current;
        if(!current.path("status").asText().equals("concluido"))throw ApiError.invalid("Conclua a tarefa antes de finalizar.");
        db.sql.update("""
            UPDATE tasks SET finalized_at=clock_timestamp(),finalized_by=?,
              finalized_by_name=(SELECT name FROM members WHERE id=?),
              finalized_project_name=(SELECT name FROM projects WHERE id=tasks.project_id),updated_at=?
            WHERE id=?
            """,actor.id(),actor.id(),Input.today(),taskId);
        return task(taskId);
    }

    private void requireNotFinalized(JsonNode task) {
        if(!task.path("finalizedAt").isNull())throw new ApiError(409,"FINALIZED","Esta tarefa foi finalizada e está disponível somente para consulta no histórico.");
    }

    private JsonNode task(UUID id) { return db.require("Tarefa não encontrada.", TASK + " WHERE t.id = ?", id); }

    private int endOf(String status) {
        return db.sql.queryForObject("SELECT COALESCE(max(position), 0) + 1 FROM tasks WHERE status = ?", Integer.class, status);
    }

    private boolean hasRestrictedTaskFields(JsonNode patch) {
        var fields = patch.fieldNames();
        while (fields.hasNext()) {
            if (!List.of("status", "checklist").contains(fields.next())) return true;
        }
        return false;
    }

    private void checkAssignee(Actor actor, JsonNode task) {
        if (!actor.admin() && !new Input(task).ids("assigneeIds").contains(actor.id())) throw ApiError.forbidden();
    }

    private void validateTransition(Actor actor, JsonNode task, String next) {
        String previous = task.path("status").asText();
        if (!actor.general() && !new Input(task).ids("assigneeIds").contains(actor.id())) {
            // Administrators may edit metadata, but cannot move another person's task.
            if (!previous.equals(next)) throw ApiError.forbidden();
        }
        if (previous.equals(next)) return;
        boolean allowed = switch (previous) {
            case "a_fazer" -> next.equals("em_andamento");
            case "em_andamento" -> next.equals("a_fazer") || next.equals("concluido");
            case "concluido" -> next.equals("em_andamento");
            default -> false;
        };
        if (!allowed) throw ApiError.invalid("Use a próxima etapa ou a anterior. No Backlog, use Aceitar tarefa.");
    }

    private record TaskData(String title, String description, UUID project, String category, String status,
                            String priority, List<UUID> assignees, List<UUID> eligible, boolean open, int max, LocalDate due, String checklist) {}

    private TaskData taskData(Input form) { return taskData(form, List.of(), true); }

    private TaskData taskData(Input form, List<UUID> oldAssignees) {
        return taskData(form,oldAssignees,false);
    }

    private TaskData taskData(Input form, List<UUID> oldAssignees, boolean creating) {
        UUID project = form.optionalId("projectId");
        if (project != null) project(project);
        List<UUID> ids = form.ids("assigneeIds");
        String status = form.choice("status", Input.TASK_STATUS);
        List<UUID> eligible = form.has("eligibleAssigneeIds") ? form.ids("eligibleAssigneeIds") : List.of();
        if (creating && status.equals("backlog")) {
            // Older clients used assigneeIds to select invitees. They are candidates, not acceptances.
            if (!form.has("eligibleAssigneeIds")) eligible = ids;
            else if (!ids.isEmpty()) throw ApiError.invalid("No Backlog, use a lista de pessoas que podem aceitar e aguarde os aceites.");
            ids = List.of();
        }
        db.validateMembers(eligible);
        db.validateMembers(ids.stream().filter(member -> !oldAssignees.contains(member)).toList());
        int max = form.number("maxAssignees", 1, 100);
        if (ids.size() > max) throw ApiError.invalid("O número de responsáveis excede o limite de vagas.");
        boolean open = status.equals("backlog") && ids.size() < max;
        if (status.equals("backlog") && ids.size() == max) status = "a_fazer";
        return new TaskData(form.text("title", 3, 200), form.text("description", 0, 12000),
            project, form.has("category") ? form.choice("category", Input.AREAS) : project != null && !project(project).path("category").isNull() ? project(project).path("category").asText() : "programacao", status,
            form.choice("priority", "baixa", "media", "alta", "critica"), ids, eligible, open, max,
            form.optionalDate("dueDate"), checklist(form.value().path("checklist")));
    }

    private String checklist(JsonNode value) {
        var items = Input.list(value, 100, node -> {
            var item = new Input(node);
            return Map.of("id", item.key("id"), "text", item.text("text", 1, 500), "done", item.bool("done"));
        });
        Input.unique(items.stream().map(item -> item.get("id")).toList());
        return json.valueToTree(items).toString();
    }

    private void notifyAssignees(UUID actor, String title, List<UUID> ids) {
        for (UUID member : ids) {
            if (!member.equals(actor)) db.sql.update(
                "INSERT INTO notifications (id, member_id, kind, title, description) SELECT ?, ?, 'tarefa', 'Nova tarefa atribuída', ? WHERE COALESCE((SELECT (preferences#>>'{notifications,taskAssigned}')::boolean FROM member_preferences WHERE member_id=?),true)",
                UUID.randomUUID(), member, "Você recebeu a tarefa \"" + title + "\".", member);
        }
    }
    private static final Map<String,String> PROJECT_STATUS = Map.of("planejamento","Planejamento","em_andamento","Em andamento","pausado","Pausado","concluido","Concluído");
    private static final Map<String,String> TASK_STATUS_LABEL = Map.of("backlog","Backlog","a_fazer","A fazer","em_andamento","Em andamento","concluido","Concluído");
    private void notifyProject(Actor actor,JsonNode project) { notifyProject(actor,project,null); }
    private void notifyProject(Actor actor,JsonNode project,JsonNode before) {
        String name=project.path("name").asText();
        String title="Projeto atualizado", description="O projeto \""+name+"\" foi atualizado.";
        if (before==null) { title="Novo projeto atribuído"; description="Você faz parte do projeto \""+name+"\"."; }
        else if (!before.path("status").asText().equals(project.path("status").asText())) {
            title="Status do projeto alterado";
            description="\""+name+"\": "+PROJECT_STATUS.getOrDefault(before.path("status").asText(),"")+" → "+PROJECT_STATUS.getOrDefault(project.path("status").asText(),"")+".";
        }
        final String heading=title, text=description;
        UUID projectId=Input.id(project.path("id").asText());
        var ids=db.sql.queryForList("""
          SELECT DISTINCT m.id FROM members m WHERE m.active AND m.id<>? AND
          (m.id=ANY(?::uuid[]) OR EXISTS(SELECT 1 FROM tasks t WHERE t.project_id=? AND m.id=ANY(t.assignee_ids)))
          AND COALESCE((SELECT (preferences#>>'{notifications,projectUpdates}')::boolean FROM member_preferences WHERE member_id=m.id),true)
          """,UUID.class,actor.id(),Store.array(new Input(project).ids("ownerIds")),projectId);
        for (UUID id:ids) db.sql.update("INSERT INTO notifications(id,member_id,kind,title,description) VALUES(?,?,'projeto',?,?)",UUID.randomUUID(),id,heading,text);
    }
    /** Avisa os responsáveis (exceto quem fez a alteração) quando a tarefa muda de coluna. */
    private void notifyTaskStatus(UUID actor,UUID taskId,String title,String oldStatus,String status) {
        if (oldStatus.equals(status)) return;
        db.sql.update("""
          INSERT INTO notifications(id,member_id,kind,title,description)
          SELECT gen_random_uuid(),m.id,'tarefa','Tarefa atualizada',? FROM tasks t JOIN members m ON m.id=ANY(t.assignee_ids) AND m.active
          WHERE t.id=? AND m.id<>? AND COALESCE((SELECT (preferences#>>'{notifications,taskAssigned}')::boolean FROM member_preferences WHERE member_id=m.id),true)
          ""","\""+title+"\" foi movida para "+TASK_STATUS_LABEL.getOrDefault(status,status)+".",taskId,actor);
    }

}
