package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firewalldo.portal.SessionService.Actor;
import java.time.LocalDate;
import java.util.ArrayList;
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
          'description', p.description, 'icon', p.icon, 'color', p.color, 'status', p.status,
          'ownerIds', p.owner_ids, 'milestones', p.milestones, 'startDate', p.start_date, 'dueDate', p.due_date,
          'progress', COALESCE(round(100 * stats.done::numeric / NULLIF(stats.total, 0)), p.progress))
        FROM projects p CROSS JOIN LATERAL (
          SELECT count(*) AS total, count(*) FILTER (WHERE status = 'concluido') AS done FROM tasks WHERE project_id = p.id
        ) stats
        """;
    private static final String TASK = """
        SELECT jsonb_build_object('id', t.id, 'title', t.title, 'description', t.description, 'projectId', t.project_id,
          'category', t.category, 'status', t.status, 'priority', t.priority, 'assigneeIds', t.assignee_ids, 'open', t.open,
          'maxAssignees', t.max_assignees, 'dueDate', t.due_date, 'checklist', t.checklist, 'createdBy', t.created_by,
          'createdAt', t.created_at, 'updatedAt', t.updated_at, 'order', t.position)
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
            INSERT INTO projects (id, name, subtitle, description, icon, color, owner_ids, start_date, due_date, milestones)
            VALUES (?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?::jsonb)
            """, id, form.text("name", 3, 150), form.text("subtitle", 0, 250), form.text("description", 0, 8000),
            form.choice("icon", "code", "layout", "smartphone", "play", "globe", "palette", "shield", "server"),
            color, Store.array(owners), Input.today(), form.date("dueDate"), milestones.toString());
        return project(id);
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
                return project(projectId);
            }
        }
        throw ApiError.missing("Marco não encontrado.");
    }

    @GetMapping("/tasks")
    List<JsonNode> tasks(@RequestParam(required = false) String projectId,
                         @RequestParam(required = false) String assigneeId,
                         @RequestParam(required = false) String status) {
        return db.list(TASK + """
            WHERE (?::uuid IS NULL OR t.project_id = ?::uuid)
              AND (?::uuid IS NULL OR ?::uuid = ANY(t.assignee_ids))
              AND (?::text IS NULL OR t.status = ?)
            ORDER BY t.position, t.id
            """, projectId == null ? null : Input.id(projectId), projectId, assigneeId == null ? null : Input.id(assigneeId),
            assigneeId, status, status);
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
        var date = Input.today();
        db.sql.update("""
            INSERT INTO tasks (id, title, description, project_id, category, status, priority, assignee_ids, open,
              max_assignees, due_date, checklist, created_by, created_at, updated_at, position)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """, id, data.title, data.description, data.project, data.category, data.status, data.priority,
            Store.array(data.assignees), data.open, data.max, data.due, data.checklist, actor.id(), date, date, endOf(data.status));
        notifyAssignees(actor.id(), data.title, data.assignees);
        return task(id);
    }

    @PatchMapping("/tasks/{id}")
    @Transactional
    JsonNode updateTask(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody JsonNode body) {
        UUID taskId = Input.id(id);
        db.lock(TASK_LOCK);
        var current = (ObjectNode) task(taskId);
        checkAssignee(actor, current);
        var patch = new Input(body);
        String oldStatus = current.path("status").asText();
        String status = patch.has("status") ? patch.choice("status", Input.TASK_STATUS) : oldStatus;
        int position = oldStatus.equals(status) ? current.path("order").asInt() : endOf(status);
        if (!actor.admin()) {
            if (hasRestrictedTaskFields(patch.value())) {
                throw ApiError.forbidden();
            }
            String checklist = patch.has("checklist") ? checklist(body.path("checklist")) : current.path("checklist").toString();
            db.sql.update("UPDATE tasks SET status = ?, position = ?, checklist = ?::jsonb, updated_at = ? WHERE id = ?",
                status, position, checklist, Input.today(), taskId);
        } else {
            patch.only("title", "description", "projectId", "category", "status", "priority", "assigneeIds",
                "open", "maxAssignees", "dueDate", "checklist");
            List<UUID> oldAssignees = new Input(current).ids("assigneeIds");
            current.setAll((ObjectNode) body);
            var data = taskData(new Input(current), oldAssignees);
            db.sql.update("""
                UPDATE tasks SET title = ?, description = ?, project_id = ?, category = ?, status = ?, priority = ?,
                  assignee_ids = ?::uuid[], open = ?, max_assignees = ?, due_date = ?, checklist = ?::jsonb,
                  position = ?, updated_at = ? WHERE id = ?
                """, data.title, data.description, data.project, data.category, data.status, data.priority,
                Store.array(data.assignees), data.open, data.max, data.due, data.checklist, position, Input.today(), taskId);
            notifyAssignees(actor.id(), data.title, data.assignees.stream().filter(member -> !oldAssignees.contains(member)).toList());
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
        checkAssignee(actor, current);
        var form = new Input(body);
        String status = form.choice("status", Input.TASK_STATUS);
        UUID beforeId = form.optionalId("beforeId");
        var column = new ArrayList<>(db.sql.queryForList(
            "SELECT id FROM tasks WHERE status = ? AND id <> ? ORDER BY position, id", UUID.class, status, taskId));
        int index = column.indexOf(beforeId);
        column.add(index < 0 ? column.size() : index, taskId);
        db.sql.update("UPDATE tasks SET status = ?, updated_at = ? WHERE id = ?", status, Input.today(), taskId);
        int position = 0;
        for (UUID member : column) db.sql.update("UPDATE tasks SET position = ? WHERE id = ?", ++position, member);
    }

    @PostMapping("/tasks/{id}/accept")
    @Transactional
    JsonNode accept(@RequestAttribute Actor actor, @PathVariable String id) {
        UUID taskId = Input.id(id);
        db.lock(TASK_LOCK);
        var current = new Input(task(taskId));
        var assignees = current.ids("assigneeIds");
        if (assignees.contains(actor.id())) return current.value();
        int limit = current.number("maxAssignees", 1, 100);
        if (!current.bool("open") || assignees.size() >= limit) {
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
        return task(taskId);
    }

    @PostMapping("/tasks/{id}/leave")
    @Transactional
    JsonNode leave(@RequestAttribute Actor actor, @PathVariable String id) {
        UUID taskId = Input.id(id);
        db.lock(TASK_LOCK);
        var current = new Input(task(taskId));
        var assignees = current.ids("assigneeIds");
        if (assignees.remove(actor.id())) {
            db.sql.update("UPDATE tasks SET assignee_ids = ?::uuid[], updated_at = ? WHERE id = ?",
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
        if (db.sql.update("DELETE FROM tasks WHERE id = ?", Input.id(id)) == 0) throw ApiError.missing("Tarefa não encontrada.");
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

    private record TaskData(String title, String description, UUID project, String category, String status,
                            String priority, List<UUID> assignees, boolean open, int max, LocalDate due, String checklist) {}

    private TaskData taskData(Input form) { return taskData(form, List.of()); }

    private TaskData taskData(Input form, List<UUID> oldAssignees) {
        UUID project = form.optionalId("projectId");
        if (project != null) project(project);
        List<UUID> ids = form.ids("assigneeIds");
        db.validateMembers(ids.stream().filter(member -> !oldAssignees.contains(member)).toList());
        int max = form.number("maxAssignees", 1, 100);
        if (ids.size() > max) throw ApiError.invalid("O número de responsáveis excede o limite de vagas.");
        boolean open = form.bool("open") && ids.size() < max;
        return new TaskData(form.text("title", 3, 200), form.text("description", 0, 12000),
            project, form.choice("category", Input.AREAS), form.choice("status", Input.TASK_STATUS),
            form.choice("priority", "baixa", "media", "alta", "critica"), ids, open, max,
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
                "INSERT INTO notifications (id, member_id, kind, title, description) VALUES (?, ?, 'tarefa', 'Nova tarefa atribuída', ?)",
                UUID.randomUUID(), member, title);
        }
    }
}
