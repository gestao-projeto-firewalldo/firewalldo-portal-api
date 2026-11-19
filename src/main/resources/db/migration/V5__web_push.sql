CREATE TABLE push_config (
  id text PRIMARY KEY CHECK(id='webpush'),
  public_key text NOT NULL,
  private_key text NOT NULL
);
CREATE TABLE push_subscriptions (
  id uuid PRIMARY KEY,
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  endpoint text UNIQUE NOT NULL,
  p256dh text NOT NULL,
  auth text NOT NULL,
  device text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE push_deliveries (
  id uuid PRIMARY KEY,
  notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  subscription_id uuid REFERENCES push_subscriptions(id) ON DELETE SET NULL,
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  title text NOT NULL DEFAULT 'Notificação push',
  status text NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','sending','accepted','failed','expired')),
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  claimed_at timestamptz,
  last_http_status integer,
  message text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(notification_id,subscription_id)
);
CREATE INDEX push_deliveries_queue_idx ON push_deliveries(status,next_attempt_at);
CREATE FUNCTION portal_queue_push() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO push_deliveries(id,notification_id,subscription_id,member_id,title)
    SELECT gen_random_uuid(),NEW.id,s.id,s.member_id,NEW.title FROM push_subscriptions s JOIN members m ON m.id=s.member_id AND m.active WHERE s.member_id=NEW.member_id;
  RETURN NEW;
END $$;
CREATE TRIGGER queue_notification_push AFTER INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION portal_queue_push();
-- Audit delivery state without subscription endpoints, auth secrets, or private VAPID keys.
CREATE TRIGGER audit_push_deliveries AFTER INSERT OR UPDATE OR DELETE ON push_deliveries FOR EACH ROW EXECUTE FUNCTION portal_audit_change();

CREATE FUNCTION portal_audit_push_subscription() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  actor uuid := NULLIF(current_setting('portal.actor_id',true),'')::uuid;
  row_id uuid;
  label text;
BEGIN
  IF TG_OP='UPDATE' AND (OLD.member_id,OLD.endpoint,OLD.p256dh,OLD.auth,OLD.device) IS NOT DISTINCT FROM (NEW.member_id,NEW.endpoint,NEW.p256dh,NEW.auth,NEW.device) THEN RETURN NEW; END IF;
  row_id:=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
  label:=CASE WHEN TG_OP='DELETE' THEN OLD.device ELSE NEW.device END;
  INSERT INTO audit_logs(actor_id,actor_name,actor_email,action,entity,entity_id,target_name,ip,details)
    VALUES(actor,COALESCE(NULLIF(current_setting('portal.actor_name',true),''),'Sistema'),COALESCE(current_setting('portal.actor_email',true),''),
      CASE TG_OP WHEN 'INSERT' THEN 'criacao' WHEN 'UPDATE' THEN 'edicao' ELSE 'exclusao' END,'push_subscriptions',row_id::text,label,
      CASE WHEN actor IS NULL THEN NULL ELSE NULLIF(current_setting('portal.ip',true),'') END,'{"fields":["aparelho"]}'::jsonb);
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER audit_push_subscriptions AFTER INSERT OR UPDATE OR DELETE ON push_subscriptions FOR EACH ROW EXECUTE FUNCTION portal_audit_push_subscription();
