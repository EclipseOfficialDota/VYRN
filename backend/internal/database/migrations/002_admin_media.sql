CREATE TABLE admin_grants (
 token_hash bytea PRIMARY KEY,
 session_id uuid NOT NULL REFERENCES sessions(id),
 expires_at timestamptz NOT NULL
);
CREATE TABLE admin_totp_used (
 user_id uuid NOT NULL REFERENCES users(id),
 step bigint NOT NULL,
 PRIMARY KEY(user_id,step)
);
ALTER TABLE media ADD COLUMN orphaned_at timestamptz;
CREATE TABLE feature_flags (
 name text PRIMARY KEY,
 enabled boolean NOT NULL DEFAULT false
);
INSERT INTO feature_flags(name) VALUES ('voice_calls'),('video_calls');
