CREATE TABLE member_preferences (
  member_id uuid PRIMARY KEY REFERENCES members(id) ON DELETE CASCADE,
  preferences jsonb NOT NULL
);
CREATE TABLE library_folders (
  id text PRIMARY KEY,
  name text NOT NULL,
  category text NOT NULL,
  color text NOT NULL
);
INSERT INTO library_folders VALUES
 ('geral','Geral','geral','#2563eb'),
 ('conteudo','Conteúdo','conteudo','#db2777'),
 ('design','Design','design','#8b5cf6'),
 ('programacao','Programação','programacao','#0891b2');
CREATE TABLE library_files (
  id uuid PRIMARY KEY,
  folder_id text NOT NULL REFERENCES library_folders(id),
  name text NOT NULL,
  kind text NOT NULL,
  media_type text NOT NULL,
  content bytea NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 5242880),
  owner_id uuid NOT NULL REFERENCES members(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION portal_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
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
  IF TG_TABLE_NAME = 'library_files' THEN
    previous := previous - 'content';
    current_row := current_row - 'content';
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

CREATE TRIGGER audit_preferences AFTER INSERT OR UPDATE OR DELETE ON member_preferences FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
CREATE TRIGGER audit_files AFTER INSERT OR UPDATE OR DELETE ON library_files FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
CREATE TRIGGER audit_folders AFTER INSERT OR UPDATE OR DELETE ON library_folders FOR EACH ROW EXECUTE FUNCTION portal_audit_change();

ALTER TABLE notifications ADD COLUMN dedup_key text UNIQUE;
