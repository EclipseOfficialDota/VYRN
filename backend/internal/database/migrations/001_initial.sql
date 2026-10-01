CREATE TABLE users (
    id uuid PRIMARY KEY,
    email text NOT NULL UNIQUE CHECK (email = lower(btrim(email))),
    password_hash text NOT NULL,
    display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
    admin boolean NOT NULL DEFAULT false,
    disabled boolean NOT NULL DEFAULT false,
    recovery_hash bytea,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE invites (
    id uuid PRIMARY KEY,
    code_hash bytea NOT NULL UNIQUE,
    remaining integer NOT NULL CHECK (remaining BETWEEN 0 AND 10000),
    expires_at timestamptz NOT NULL,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    device_id uuid NOT NULL,
    device_name text NOT NULL CHECK (char_length(device_name) BETWEEN 1 AND 100),
    access_hash bytea NOT NULL UNIQUE,
    refresh_hash bytea NOT NULL UNIQUE,
    access_expires timestamptz NOT NULL,
    refresh_expires timestamptz NOT NULL,
    revoked_at timestamptz,
    cursor bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX sessions_active_device ON sessions(user_id,device_id) WHERE revoked_at IS NULL;
CREATE TABLE used_refresh_tokens (
    token_hash bytea PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES sessions(id),
    expires_at timestamptz NOT NULL
);
CREATE TABLE auth_attempts (
    key bytea PRIMARY KEY,
    failures integer NOT NULL DEFAULT 0,
    locked_until timestamptz NOT NULL DEFAULT '-infinity',
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE conversations (
    id uuid PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('private','group')),
    title text NOT NULL DEFAULT '' CHECK (char_length(title) <= 100),
    private_pair text UNIQUE,
    owner_id uuid NOT NULL REFERENCES users(id),
    disappearing_seconds integer NOT NULL DEFAULT 0 CHECK(disappearing_seconds BETWEEN 0 AND 31536000),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE members (
    conversation_id uuid NOT NULL REFERENCES conversations(id),
    user_id uuid NOT NULL REFERENCES users(id),
    role text NOT NULL CHECK(role IN ('owner','admin','member')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(conversation_id,user_id)
);
CREATE INDEX members_user ON members(user_id,conversation_id);
CREATE TABLE blocks (
    user_id uuid NOT NULL REFERENCES users(id),
    blocked_id uuid NOT NULL REFERENCES users(id),
    PRIMARY KEY(user_id,blocked_id),
    CHECK(user_id<>blocked_id)
);
CREATE TABLE media (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    conversation_id uuid REFERENCES conversations(id),
    kind text NOT NULL CHECK(kind IN ('file','image','video','voice','avatar')),
    filename text NOT NULL,
    content_type text NOT NULL,
    size bigint NOT NULL CHECK(size>=0 AND size<=262144000),
    sha256 text NOT NULL,
    storage_key text NOT NULL UNIQUE,
    encrypted boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE users ADD COLUMN avatar_id uuid REFERENCES media(id);
CREATE TABLE messages (
    id uuid PRIMARY KEY,
    client_id uuid NOT NULL,
    conversation_id uuid NOT NULL REFERENCES conversations(id),
    sender_id uuid NOT NULL REFERENCES users(id),
    sender_device uuid NOT NULL,
    payload jsonb NOT NULL,
    encrypted boolean NOT NULL,
    media_id uuid REFERENCES media(id),
    reply_to uuid REFERENCES messages(id),
    forwarded_from uuid REFERENCES messages(id),
    request_hash bytea NOT NULL,
    revision integer NOT NULL DEFAULT 1,
    pinned boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    edited_at timestamptz,
    deleted_at timestamptz,
    expires_at timestamptz,
    UNIQUE(sender_id,client_id)
);
CREATE INDEX messages_page ON messages(conversation_id,created_at DESC,id DESC);
CREATE INDEX messages_expiry ON messages(expires_at) WHERE expires_at IS NOT NULL AND deleted_at IS NULL;
CREATE TABLE reactions (
    message_id uuid NOT NULL REFERENCES messages(id),
    user_id uuid NOT NULL REFERENCES users(id),
    emoji text NOT NULL CHECK(char_length(emoji) BETWEEN 1 AND 16),
    PRIMARY KEY(message_id,user_id,emoji)
);
CREATE TABLE receipts (
    message_id uuid NOT NULL REFERENCES messages(id),
    user_id uuid NOT NULL REFERENCES users(id),
    delivered_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz,
    PRIMARY KEY(message_id,user_id)
);
CREATE TABLE drafts (
    conversation_id uuid NOT NULL REFERENCES conversations(id),
    user_id uuid NOT NULL REFERENCES users(id),
    payload jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(conversation_id,user_id)
);
CREATE TABLE events (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    kind text NOT NULL,
    body jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX events_resume ON events(user_id,sequence);
CREATE TABLE device_keys (
    user_id uuid NOT NULL REFERENCES users(id),
    device_id uuid NOT NULL,
    identity_key text NOT NULL,
    signed_prekey jsonb NOT NULL,
    registration_id integer NOT NULL,
    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,device_id)
);
CREATE TABLE one_time_prekeys (
    user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    key_id integer NOT NULL,
    public_key text NOT NULL,
    PRIMARY KEY(user_id,device_id,key_id),
    FOREIGN KEY(user_id,device_id) REFERENCES device_keys(user_id,device_id)
);
CREATE TABLE audit_log (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id uuid REFERENCES users(id),
    action text NOT NULL,
    target_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
