-- Firewalldo Portal V1.9
-- Migração aditiva: nenhum dado existente é apagado.

-- 1) Radar de notícias manual (fim da coleta automática via OpenAI)
UPDATE radar_settings SET enabled = false;
ALTER TABLE news ALTER COLUMN source_id DROP NOT NULL;
ALTER TABLE news ALTER COLUMN url DROP NOT NULL;
ALTER TABLE news DROP CONSTRAINT IF EXISTS news_url_key;
ALTER TABLE news ADD COLUMN slot text CHECK (slot IN ('principal', 'secundaria', 'geral'));
ALTER TABLE news ADD COLUMN source_name text NOT NULL DEFAULT '';
ALTER TABLE news ADD COLUMN created_by uuid REFERENCES members(id) ON DELETE SET NULL;
ALTER TABLE news ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
-- Garante no banco que nunca existam duas notícias principais, mesmo com acessos simultâneos.
CREATE UNIQUE INDEX news_single_principal ON news (slot) WHERE slot = 'principal';
CREATE INDEX news_slot_idx ON news (slot, published_at DESC) WHERE slot IS NOT NULL;

-- 2) Fotos de perfil (guardadas no PostgreSQL: sobrevivem a deploys e reinícios)
CREATE TABLE member_photos (
  member_id uuid PRIMARY KEY REFERENCES members(id) ON DELETE CASCADE,
  content bytea NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 1048576),
  media_type text NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg', 'image/webp')),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- 3) Financeiro
ALTER TABLE members ADD COLUMN finance_access boolean NOT NULL DEFAULT false;

CREATE TABLE finance_investments (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  supplier_name text NOT NULL DEFAULT '',
  legal_name text NOT NULL DEFAULT '',
  document text NOT NULL DEFAULT '',
  category text NOT NULL,
  description text NOT NULL DEFAULT '',
  owner_id uuid REFERENCES members(id) ON DELETE SET NULL,
  amount_cents bigint NOT NULL CHECK (amount_cents > 0),
  billing text NOT NULL CHECK (billing IN ('unica', 'recorrente')),
  frequency text CHECK (frequency IN ('mensal', 'trimestral', 'semestral', 'anual')),
  start_date date NOT NULL,
  due_day integer CHECK (due_day BETWEEN 1 AND 31),
  end_date date,
  status text NOT NULL DEFAULT 'ativo' CHECK (status IN ('ativo', 'pausado', 'encerrado')),
  payment_method text NOT NULL CHECK (payment_method IN ('pix', 'boleto')),
  pix_key_type text CHECK (pix_key_type IN ('cpf', 'cnpj', 'email', 'telefone', 'aleatoria')),
  pix_key text NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  created_by uuid REFERENCES members(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((billing = 'recorrente') = (frequency IS NOT NULL)),
  CHECK (end_date IS NULL OR end_date >= start_date)
);

-- Pix e boletos compartilham a mesma tabela (campo kind). Ocorrências de cobranças
-- recorrentes têm investment_id + period e nunca são duplicadas (índice único).
CREATE TABLE finance_payments (
  id uuid PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('pix', 'boleto')),
  investment_id uuid REFERENCES finance_investments(id) ON DELETE SET NULL,
  period text CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
  beneficiary text NOT NULL,
  document text NOT NULL DEFAULT '',
  description text NOT NULL DEFAULT '',
  amount_cents bigint NOT NULL CHECK (amount_cents > 0),
  due_date date NOT NULL,
  status text NOT NULL DEFAULT 'pendente' CHECK (status IN ('pendente', 'pago', 'cancelado')),
  paid_at date,
  pix_key_type text CHECK (pix_key_type IN ('cpf', 'cnpj', 'email', 'telefone', 'aleatoria')),
  pix_key text NOT NULL DEFAULT '',
  barcode text NOT NULL DEFAULT '',
  attachment bytea CHECK (octet_length(attachment) <= 4194304),
  attachment_type text,
  attachment_name text NOT NULL DEFAULT '',
  receipt bytea CHECK (octet_length(receipt) <= 4194304),
  receipt_type text,
  receipt_name text NOT NULL DEFAULT '',
  created_by uuid REFERENCES members(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'pago') = (paid_at IS NOT NULL))
);
CREATE UNIQUE INDEX finance_payments_occurrence ON finance_payments (investment_id, period)
  WHERE investment_id IS NOT NULL AND period IS NOT NULL;
CREATE INDEX finance_payments_kind_due_idx ON finance_payments (kind, due_date DESC);

-- 4) Auditoria: arquivos binários nunca entram no JSON de auditoria (só os nomes dos campos).
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
  info:=jsonb_build_object('fields',COALESCE(fields,ARRAY[]::text[]));
  IF TG_TABLE_NAME='members' THEN
    info:=info||jsonb_build_object('previousRole',previous->'role','role',current_row->'role',
      'passwordChanged','senha'=ANY(COALESCE(fields,ARRAY[]::text[])) OR (TG_OP='INSERT' AND NEW.password_hash IS NOT NULL));
  END IF;
  INSERT INTO audit_logs(actor_id,actor_name,actor_email,action,entity,entity_id,target_name,ip,details)
    VALUES(actor,actor_label,actor_email,CASE TG_OP WHEN 'INSERT' THEN 'criacao' WHEN 'UPDATE' THEN 'edicao' ELSE 'exclusao' END,
      TG_TABLE_NAME,COALESCE(identity_row->>'id',identity_row->>'news_id',identity_row->>'idea_id',identity_row->>'member_id',''),
      COALESCE(identity_row->>'name',identity_row->>'title',identity_row->>'label',identity_row->>'beneficiary',''),CASE WHEN actor IS NULL THEN NULL ELSE address END,info);
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER audit_changes AFTER INSERT OR UPDATE OR DELETE ON member_photos FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
CREATE TRIGGER audit_changes AFTER INSERT OR UPDATE OR DELETE ON finance_investments FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
CREATE TRIGGER audit_changes AFTER INSERT OR UPDATE OR DELETE ON finance_payments FOR EACH ROW EXECUTE FUNCTION portal_audit_change();
