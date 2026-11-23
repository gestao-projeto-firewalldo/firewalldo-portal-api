CREATE TABLE driver_fields (
  id uuid PRIMARY KEY,
  name text NOT NULL CHECK(length(name) BETWEEN 2 AND 120),
  description text NOT NULL DEFAULT '' CHECK(length(description)<=4000),
  image_content bytea,
  image_type text,
  created_by uuid NOT NULL REFERENCES members(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((image_content IS NULL AND image_type IS NULL) OR
    (image_content IS NOT NULL AND image_type IN ('image/png','image/jpeg','image/webp')))
);
CREATE TABLE driver_shortcuts (
  id uuid PRIMARY KEY,
  field_id uuid NOT NULL REFERENCES driver_fields(id) ON DELETE CASCADE,
  name text NOT NULL CHECK(length(name) BETWEEN 2 AND 120),
  url text NOT NULL CHECK(length(url) BETWEEN 1 AND 2048),
  image_content bytea,
  image_type text,
  created_by uuid NOT NULL REFERENCES members(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((image_content IS NULL AND image_type IS NULL) OR
    (image_content IS NOT NULL AND image_type IN ('image/png','image/jpeg','image/webp')))
);
CREATE INDEX driver_shortcuts_field_idx ON driver_shortcuts(field_id,created_at,id);
CREATE TRIGGER audit_changes AFTER INSERT OR UPDATE OR DELETE ON driver_fields
 FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
CREATE TRIGGER audit_changes AFTER INSERT OR UPDATE OR DELETE ON driver_shortcuts
 FOR EACH ROW EXECUTE FUNCTION portal_audit_change();

-- Driver images stay out of durable audit JSON; business metadata remains visible.
CREATE OR REPLACE FUNCTION portal_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  previous jsonb := CASE WHEN TG_OP='INSERT' THEN '{}'::jsonb ELSE to_jsonb(OLD) END;
  current_row jsonb := CASE WHEN TG_OP='DELETE' THEN '{}'::jsonb ELSE to_jsonb(NEW) END;
  identity_row jsonb;
  fields text[];
  actor uuid := NULLIF(current_setting('portal.actor_id',true),'')::uuid;
  actor_label text := COALESCE(NULLIF(current_setting('portal.actor_name',true),''),'Sistema');
  actor_email text := COALESCE(current_setting('portal.actor_email',true),'');
  address text := NULLIF(current_setting('portal.ip',true),'');
  info jsonb;
  changes jsonb;
BEGIN
  IF TG_TABLE_NAME='members' THEN
    IF TG_OP='UPDATE' AND previous->'password_hash' IS DISTINCT FROM current_row->'password_hash' THEN
      previous := previous || '{"senha":"alterada"}'::jsonb;
      current_row := current_row || '{"senha":"definida"}'::jsonb;
    END IF;
    previous := previous - 'password_hash' - 'last_seen'; current_row := current_row - 'password_hash' - 'last_seen';
  END IF;
  IF TG_TABLE_NAME='invitations' THEN previous:=previous-'token_hash'; current_row:=current_row-'token_hash'; END IF;
  IF TG_TABLE_NAME='library_files' THEN previous:=previous-'content'; current_row:=current_row-'content'; END IF;
  IF TG_TABLE_NAME IN ('news','driver_fields','driver_shortcuts') THEN previous:=previous-'image_content'; current_row:=current_row-'image_content'; END IF;
  IF TG_TABLE_NAME='member_photos' THEN
    previous:=previous-'content'||jsonb_build_object('foto',previous->>'updated_at');
    current_row:=current_row-'content'||jsonb_build_object('foto',current_row->>'updated_at');
  END IF;
  IF TG_TABLE_NAME='finance_payments' THEN
    previous:=previous-'attachment'-'receipt'; current_row:=current_row-'attachment'-'receipt';
  END IF;
  IF TG_OP='UPDATE' AND previous=current_row THEN RETURN NEW; END IF;
  SELECT array_agg(key ORDER BY key) INTO fields FROM (
    SELECT key FROM jsonb_object_keys(previous||current_row) AS k(key) WHERE previous->key IS DISTINCT FROM current_row->key
  ) changed;
  identity_row := CASE WHEN TG_OP='DELETE' THEN previous ELSE current_row END;
  -- Only explicitly permitted business fields get before/after values.
  -- Passwords, tokens, push keys, binary files, Pix keys and banking documents stay out.
  SELECT COALESCE(jsonb_object_agg(key,jsonb_build_object('before',previous->key,'after',current_row->key)),'{}'::jsonb)
    INTO changes FROM unnest(COALESCE(fields,ARRAY[]::text[])) AS changed(key)
    WHERE key=ANY(ARRAY['field_id','name','title','label','subtitle','description','status','priority','category','brand',
      'finalized_at','finalized_by','finalized_by_name','finalized_project_name','url','image_type',
      'role','active','finance_access','areas','eligible_assignee_ids','assignee_ids','owner_ids','project_id','due_date','start_date','end_date',
      'open','max_assignees','checklist','milestones','ticket_number','config','next_run_at','deleted',
      'schedule_id','scheduled_for','schedule_member_id','slot','source_name','featured','summary','body',
      'amount_cents','billing','frequency','due_day','payment_method','kind','beneficiary','paid_at',
      'attachment_name','receipt_name','media_type','folder_id','owner_id','preferences','read','foto']);
  info:=jsonb_build_object('fields',COALESCE(fields,ARRAY[]::text[]),'changes',changes,
    'actorRole',NULLIF(current_setting('portal.actor_role',true),''),
    'method',NULLIF(current_setting('portal.method',true),''),'route',NULLIF(current_setting('portal.route',true),''));
  IF TG_TABLE_NAME='members' THEN
    info:=info||jsonb_build_object('previousRole',previous->'role','role',current_row->'role',
      'passwordChanged','senha'=ANY(COALESCE(fields,ARRAY[]::text[])) OR (TG_OP='INSERT' AND NEW.password_hash IS NOT NULL));
  END IF;
  INSERT INTO audit_logs(actor_id,actor_name,actor_email,action,entity,entity_id,target_name,ip,details)
    VALUES(actor,actor_label,actor_email,CASE WHEN TG_TABLE_NAME='tasks' AND TG_OP='UPDATE' AND previous->>'finalized_at' IS NULL AND current_row->>'finalized_at' IS NOT NULL THEN 'finalizacao'
        WHEN TG_TABLE_NAME IN ('tasks','projects') AND TG_OP='UPDATE' AND previous->>'status' IS DISTINCT FROM 'concluido' AND current_row->>'status'='concluido' THEN 'conclusao'
        ELSE CASE TG_OP WHEN 'INSERT' THEN 'criacao' WHEN 'UPDATE' THEN 'edicao' ELSE 'exclusao' END END,
      TG_TABLE_NAME,COALESCE(identity_row->>'id',identity_row->>'news_id',identity_row->>'idea_id',identity_row->>'member_id',''),
      COALESCE(identity_row->>'name',identity_row->>'title',identity_row->>'label',identity_row->>'beneficiary',''),CASE WHEN actor IS NULL THEN NULL ELSE address END,info);
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
