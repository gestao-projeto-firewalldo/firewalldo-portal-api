CREATE TABLE members (
  id uuid PRIMARY KEY,
  email text UNIQUE NOT NULL,
  password_hash text,
  name text NOT NULL,
  role text NOT NULL CHECK (role IN ('admin', 'usuario')),
  title text NOT NULL DEFAULT '',
  bio text NOT NULL DEFAULT '',
  areas text[] NOT NULL DEFAULT '{}',
  active boolean NOT NULL DEFAULT true,
  last_seen timestamptz
);

CREATE TABLE sessions (
  token_hash text PRIMARY KEY,
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_member_idx ON sessions(member_id);

CREATE TABLE invitations (
  token_hash text PRIMARY KEY,
  member_id uuid UNIQUE NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL
);

CREATE TABLE login_attempts (
  key text PRIMARY KEY,
  started_at timestamptz NOT NULL,
  count integer NOT NULL
);

CREATE TABLE projects (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  subtitle text NOT NULL,
  description text NOT NULL,
  icon text NOT NULL,
  color text NOT NULL,
  status text NOT NULL DEFAULT 'planejamento',
  owner_ids uuid[] NOT NULL,
  milestones jsonb NOT NULL DEFAULT '[]',
  start_date date NOT NULL,
  due_date date NOT NULL,
  progress integer NOT NULL DEFAULT 0
);

CREATE TABLE tasks (
  id uuid PRIMARY KEY,
  project_id uuid REFERENCES projects(id),
  title text NOT NULL,
  description text NOT NULL,
  category text NOT NULL,
  status text NOT NULL,
  priority text NOT NULL,
  assignee_ids uuid[] NOT NULL,
  open boolean NOT NULL,
  max_assignees integer NOT NULL CHECK (max_assignees >= 1 AND cardinality(assignee_ids) <= max_assignees),
  due_date date,
  checklist jsonb NOT NULL,
  created_by uuid NOT NULL REFERENCES members(id),
  created_at date NOT NULL,
  updated_at date NOT NULL,
  position integer NOT NULL
);
CREATE INDEX tasks_project_idx ON tasks(project_id);
CREATE INDEX tasks_position_idx ON tasks(status, position);

CREATE TABLE ideas (
  id uuid PRIMARY KEY,
  author_id uuid NOT NULL REFERENCES members(id),
  title text NOT NULL,
  description text NOT NULL,
  category text NOT NULL,
  status text NOT NULL DEFAULT 'nova',
  created_at date NOT NULL
);
CREATE TABLE idea_votes (
  idea_id uuid REFERENCES ideas(id) ON DELETE CASCADE,
  member_id uuid REFERENCES members(id) ON DELETE CASCADE,
  PRIMARY KEY (idea_id, member_id)
);

CREATE TABLE radar_sources (
  id text PRIMARY KEY,
  name text NOT NULL,
  description text NOT NULL,
  url text NOT NULL,
  short text NOT NULL,
  active boolean NOT NULL,
  position integer NOT NULL
);
CREATE TABLE news (
  id uuid PRIMARY KEY,
  source_id text NOT NULL,
  title text NOT NULL,
  summary text NOT NULL,
  body text[] NOT NULL,
  category text NOT NULL,
  url text UNIQUE NOT NULL,
  published_at date NOT NULL,
  read_minutes integer NOT NULL,
  featured boolean NOT NULL,
  cover text NOT NULL
);
CREATE TABLE saved_news (
  news_id uuid REFERENCES news(id) ON DELETE CASCADE,
  member_id uuid REFERENCES members(id) ON DELETE CASCADE,
  PRIMARY KEY (news_id, member_id)
);
CREATE TABLE shortcuts (
  id text PRIMARY KEY,
  label text NOT NULL,
  url text NOT NULL,
  icon text NOT NULL,
  position integer NOT NULL
);

CREATE TABLE notifications (
  id uuid PRIMARY KEY,
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  kind text NOT NULL,
  title text NOT NULL,
  description text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  read boolean NOT NULL DEFAULT false
);
CREATE INDEX notifications_member_idx ON notifications(member_id, created_at DESC);

INSERT INTO shortcuts (id, label, url, icon, position) VALUES
  ('sc-drive', 'Google Drive', '', 'drive', 0),
  ('sc-youtube', 'YouTube', '', 'youtube', 1),
  ('sc-instagram', 'Instagram', '', 'instagram', 2),
  ('sc-tiktok', 'TikTok', '', 'tiktok', 3),
  ('sc-landing', 'Landing Page', '', 'landing', 4),
  ('sc-mindmap', 'Mapa mental', '', 'mindmap', 5);

INSERT INTO radar_sources (id, name, description, url, short, active, position) VALUES
  ('src-cert', 'CERT.br', 'Orientações e comunicados de segurança.', 'https://www.cert.br/', 'CB', true, 0),
  ('src-msrc', 'Microsoft Security Response Center', 'Comunicados de segurança da Microsoft.', 'https://msrc.microsoft.com/', 'MS', true, 1);
