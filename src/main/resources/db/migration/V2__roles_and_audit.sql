ALTER TABLE members DROP CONSTRAINT members_role_check;
ALTER TABLE members ADD CONSTRAINT members_role_check CHECK (role IN ('superadmin','admin','usuario'));

CREATE TABLE audit_logs (
  id bigserial PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  actor_id uuid,
  actor_name text NOT NULL,
  actor_email text NOT NULL DEFAULT '',
  action text NOT NULL,
  entity text NOT NULL,
  entity_id text NOT NULL DEFAULT '',
  target_name text NOT NULL DEFAULT '',
  ip text,
  outcome text NOT NULL DEFAULT 'sucesso',
  details jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX audit_created_idx ON audit_logs(created_at DESC, id DESC);
CREATE INDEX audit_action_idx ON audit_logs(action, created_at DESC);
CREATE INDEX audit_actor_idx ON audit_logs(actor_id, created_at DESC);

CREATE FUNCTION portal_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  previous jsonb := CASE WHEN TG_OP = 'INSERT' THEN '{}'::jsonb ELSE to_jsonb(OLD) END;
  current_row jsonb := CASE WHEN TG_OP = 'DELETE' THEN '{}'::jsonb ELSE to_jsonb(NEW) END;
  identity_row jsonb;
  fields text[];
  actor uuid := NULLIF(current_setting('portal.actor_id', true), '')::uuid;
  actor_label text := COALESCE(NULLIF(current_setting('portal.actor_name', true), ''), 'Sistema');
  actor_email text := COALESCE(current_setting('portal.actor_email', true), '');
  address text := NULLIF(current_setting('portal.ip', true), '');
  info jsonb;
BEGIN
  -- Last-seen heartbeats and password hashes are never stored in the audit trail.
  IF TG_TABLE_NAME = 'members' THEN
    IF TG_OP = 'UPDATE' AND previous->'password_hash' IS DISTINCT FROM current_row->'password_hash' THEN
      previous := previous || '{"senha":"alterada"}'::jsonb;
      current_row := current_row || '{"senha":"definida"}'::jsonb;
    END IF;
    previous := previous - 'password_hash' - 'last_seen';
    current_row := current_row - 'password_hash' - 'last_seen';
  END IF;
  IF TG_TABLE_NAME = 'invitations' THEN
    previous := previous - 'token_hash';
    current_row := current_row - 'token_hash';
  END IF;
  IF TG_OP = 'UPDATE' AND previous = current_row THEN RETURN NEW; END IF;
  SELECT array_agg(key ORDER BY key) INTO fields FROM (
    SELECT key FROM jsonb_object_keys(previous || current_row) AS k(key)
    WHERE previous->key IS DISTINCT FROM current_row->key
  ) changed;
  identity_row := CASE WHEN TG_OP = 'DELETE' THEN previous ELSE current_row END;
  info := jsonb_build_object('fields', COALESCE(fields, ARRAY[]::text[]));
  IF TG_TABLE_NAME = 'members' THEN
    info := info || jsonb_build_object('previousRole', previous->'role', 'role', current_row->'role',
      'passwordChanged', 'senha' = ANY(COALESCE(fields, ARRAY[]::text[])) OR (TG_OP = 'INSERT' AND NEW.password_hash IS NOT NULL));
  END IF;
  INSERT INTO audit_logs(actor_id, actor_name, actor_email, action, entity, entity_id, target_name, ip, details)
    VALUES(actor, actor_label, actor_email,
      CASE TG_OP WHEN 'INSERT' THEN 'criacao' WHEN 'UPDATE' THEN 'edicao' ELSE 'exclusao' END,
      TG_TABLE_NAME, COALESCE(identity_row->>'id', identity_row->>'news_id', identity_row->>'idea_id', identity_row->>'member_id', ''),
      COALESCE(identity_row->>'name', identity_row->>'title', identity_row->>'label', ''),
      CASE WHEN actor IS NULL THEN NULL ELSE address END, info);
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

DO $$ DECLARE tab text; BEGIN
  FOREACH tab IN ARRAY ARRAY['members','projects','tasks','ideas','idea_votes','news','saved_news','radar_sources','shortcuts','notifications','invitations'] LOOP
    EXECUTE format('CREATE TRIGGER audit_changes AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION portal_audit_change()', tab);
  END LOOP;
END $$;

CREATE FUNCTION portal_protect_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Os registros de auditoria são somente leitura.'; END $$;
CREATE TRIGGER audit_read_only BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_logs
  FOR EACH STATEMENT EXECUTE FUNCTION portal_protect_audit();
