-- Future openings are shared; historical individual tasks and tickets stay intact.
UPDATE task_schedules SET config = config || jsonb_build_object(
  'initialStatus', COALESCE(config->>'initialStatus','a_fazer'),
  'maxAssignees', COALESCE((config->>'maxAssignees')::integer,
    GREATEST(1,jsonb_array_length(config->'assigneeIds'))));

ALTER TABLE tasks DROP CONSTRAINT tasks_schedule_individual_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_schedule_individual_check CHECK (
  (schedule_id IS NULL AND scheduled_for IS NULL AND schedule_member_id IS NULL)
  OR (schedule_id IS NOT NULL AND scheduled_for IS NOT NULL AND
    (schedule_member_id IS NULL OR (cardinality(assignee_ids)=1
      AND assignee_ids[1]=schedule_member_id AND NOT open AND max_assignees=1)))
);
CREATE UNIQUE INDEX tasks_schedule_shared_idx ON tasks(schedule_id,scheduled_for)
  WHERE schedule_id IS NOT NULL AND schedule_member_id IS NULL;
ALTER TABLE task_schedule_runs ALTER COLUMN member_id DROP NOT NULL;
CREATE UNIQUE INDEX task_schedule_runs_shared_idx ON task_schedule_runs(schedule_id,scheduled_for)
  WHERE member_id IS NULL;
