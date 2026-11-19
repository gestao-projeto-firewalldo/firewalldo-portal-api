ALTER TABLE projects ADD COLUMN category text CHECK(category IN ('programacao','design','conteudo','marketing','infraestrutura','gestao'));
ALTER TABLE projects ADD COLUMN brand text NOT NULL DEFAULT '';

ALTER TABLE news ADD COLUMN publication_status text NOT NULL DEFAULT 'published' CHECK(publication_status IN ('draft','published'));
ALTER TABLE news ADD COLUMN imported_at timestamptz;
ALTER TABLE news ADD COLUMN dedup_key text UNIQUE;
ALTER TABLE news ADD COLUMN references_json jsonb NOT NULL DEFAULT '[]';
ALTER TABLE news ADD COLUMN image_content bytea CHECK(octet_length(image_content) <= 2097152);
ALTER TABLE news ADD COLUMN image_type text;
ALTER TABLE news ADD COLUMN image_credit text NOT NULL DEFAULT '';
ALTER TABLE news ADD COLUMN image_source_url text NOT NULL DEFAULT '';
ALTER TABLE news ADD COLUMN image_illustrative boolean NOT NULL DEFAULT false;

CREATE TABLE radar_settings (
  id text PRIMARY KEY CHECK(id='radar'),
  enabled boolean NOT NULL DEFAULT false,
  interval_minutes integer NOT NULL DEFAULT 180 CHECK(interval_minutes BETWEEN 15 AND 1440),
  lookback_hours integer NOT NULL DEFAULT 24 CHECK(lookback_hours BETWEEN 1 AND 168),
  max_items integer NOT NULL DEFAULT 5 CHECK(max_items BETWEEN 1 AND 10),
  criteria text NOT NULL DEFAULT 'Notícias de alto impacto sobre cibersegurança, firewalls, redes e inteligência artificial, com relevância para o público brasileiro. Priorize comunicados oficiais, incidentes relevantes e inovações de grandes marcas. Ignore curiosidades e atualizações pequenas.',
  auto_publish boolean NOT NULL DEFAULT true,
  fetch_images boolean NOT NULL DEFAULT true,
  next_run_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO radar_settings(id) VALUES('radar');
CREATE TABLE radar_runs (
  id uuid PRIMARY KEY,
  title text NOT NULL DEFAULT 'Coleta do Radar',
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  status text NOT NULL CHECK(status IN ('running','success','partial','failed')),
  imported integer NOT NULL DEFAULT 0,
  skipped integer NOT NULL DEFAULT 0,
  message text NOT NULL DEFAULT ''
);
CREATE INDEX radar_runs_started_idx ON radar_runs(started_at DESC);

-- Keep large images out of audit JSON. The underlying trigger records field names only.
ALTER FUNCTION portal_audit_change() RENAME TO portal_audit_change_v3;
CREATE FUNCTION portal_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
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
  IF TG_TABLE_NAME='news' THEN previous:=previous-'image_content'; current_row:=current_row-'image_content'; END IF;
  IF TG_OP='UPDATE' AND previous=current_row THEN RETURN NEW; END IF;
  SELECT array_agg(key ORDER BY key) INTO fields FROM (
    SELECT key FROM jsonb_object_keys(previous||current_row) AS k(key) WHERE previous->key IS DISTINCT FROM current_row->key
  ) changed;
  identity_row := CASE WHEN TG_OP='DELETE' THEN previous ELSE current_row END;
  info:=jsonb_build_object('fields',COALESCE(fields,ARRAY[]::text[]));
  IF TG_TABLE_NAME='members' THEN
    info:=info||jsonb_build_object('previousRole',previous->'role','role',current_row->'role',
      'passwordChanged','senha'=ANY(COALESCE(fields,ARRAY[]::text[])) OR (TG_OP='INSERT' AND NEW.password_hash IS NOT NULL));
  END IF;
  INSERT INTO audit_logs(actor_id,actor_name,actor_email,action,entity,entity_id,target_name,ip,details)
    VALUES(actor,actor_label,actor_email,CASE TG_OP WHEN 'INSERT' THEN 'criacao' WHEN 'UPDATE' THEN 'edicao' ELSE 'exclusao' END,
      TG_TABLE_NAME,COALESCE(identity_row->>'id',identity_row->>'news_id',identity_row->>'idea_id',identity_row->>'member_id',''),
      COALESCE(identity_row->>'name',identity_row->>'title',identity_row->>'label',''),CASE WHEN actor IS NULL THEN NULL ELSE address END,info);
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
-- Existing triggers reference the old function OID; recreate them to use the new implementation.
DO $$ DECLARE t record; BEGIN
  FOR t IN SELECT tgname,c.relname FROM pg_trigger g JOIN pg_class c ON c.oid=g.tgrelid WHERE g.tgfoid='portal_audit_change_v3()'::regprocedure LOOP
    EXECUTE format('DROP TRIGGER %I ON %I',t.tgname,t.relname);
    EXECUTE format('CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION portal_audit_change()',t.tgname,t.relname);
  END LOOP;
END $$;
DROP FUNCTION portal_audit_change_v3();
CREATE TRIGGER audit_radar_settings AFTER INSERT OR UPDATE OR DELETE ON radar_settings FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
CREATE TRIGGER audit_radar_runs AFTER INSERT OR UPDATE OR DELETE ON radar_runs FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
