CREATE TABLE task_schedules (
  id uuid PRIMARY KEY,
  title text NOT NULL,
  config jsonb NOT NULL,
  active boolean NOT NULL DEFAULT true,
  deleted boolean NOT NULL DEFAULT false,
  created_by uuid NOT NULL REFERENCES members(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  next_run_at timestamptz
);
CREATE INDEX task_schedules_due_idx ON task_schedules(next_run_at) WHERE active AND NOT deleted;
ALTER TABLE tasks ADD COLUMN schedule_id uuid REFERENCES task_schedules(id);
ALTER TABLE tasks ADD COLUMN scheduled_for timestamptz;
ALTER TABLE tasks ADD COLUMN schedule_member_id uuid REFERENCES members(id);
ALTER TABLE tasks ADD CONSTRAINT tasks_schedule_individual_check CHECK (
  (schedule_id IS NULL AND scheduled_for IS NULL AND schedule_member_id IS NULL)
  OR (schedule_id IS NOT NULL AND scheduled_for IS NOT NULL AND schedule_member_id IS NOT NULL
      AND cardinality(assignee_ids)=1 AND assignee_ids[1]=schedule_member_id AND NOT open AND max_assignees=1)
);
CREATE UNIQUE INDEX tasks_schedule_occurrence_idx ON tasks(schedule_id, scheduled_for, schedule_member_id);
CREATE INDEX tasks_assignees_idx ON tasks USING gin(assignee_ids);
-- Kept even if an administrator deletes a generated task: a retry must not recreate it.
CREATE TABLE task_schedule_runs (
  id uuid PRIMARY KEY,
  schedule_id uuid NOT NULL REFERENCES task_schedules(id),
  scheduled_for timestamptz NOT NULL,
  member_id uuid NOT NULL REFERENCES members(id),
  task_id uuid REFERENCES tasks(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(schedule_id, scheduled_for, member_id)
);
CREATE TRIGGER audit_changes AFTER INSERT OR UPDATE OR DELETE ON task_schedules
 FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
CREATE TRIGGER audit_changes AFTER INSERT OR UPDATE OR DELETE ON task_schedule_runs
 FOR EACH ROW EXECUTE FUNCTION portal_audit_change();

-- A transactional counter gives every actual launch its own consecutive number.
-- Rollbacks do not consume a number; deletion never rewinds the counter.
ALTER TABLE tasks ADD COLUMN ticket_number bigint;
WITH numbered AS (
  SELECT id,row_number() OVER(ORDER BY created_at,position,id) AS number FROM tasks
) UPDATE tasks t SET ticket_number=n.number FROM numbered n WHERE n.id=t.id;
ALTER TABLE tasks ALTER COLUMN ticket_number SET NOT NULL;
ALTER TABLE tasks ADD CONSTRAINT tasks_ticket_number_unique UNIQUE(ticket_number);
ALTER TABLE tasks ADD CONSTRAINT tasks_ticket_number_positive CHECK(ticket_number>0);
CREATE TABLE task_ticket_counter (
  id boolean PRIMARY KEY DEFAULT true CHECK(id),
  last_number bigint NOT NULL CHECK(last_number>=0)
);
INSERT INTO task_ticket_counter(id,last_number) SELECT true,COALESCE(max(ticket_number),0) FROM tasks;
CREATE FUNCTION portal_task_ticket() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    UPDATE task_ticket_counter SET last_number=last_number+1 WHERE id RETURNING last_number INTO NEW.ticket_number;
  ELSIF NEW.ticket_number IS DISTINCT FROM OLD.ticket_number THEN
    RAISE EXCEPTION 'O número do ticket não pode ser alterado.';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tasks_ticket BEFORE INSERT OR UPDATE ON tasks FOR EACH ROW EXECUTE FUNCTION portal_task_ticket();
