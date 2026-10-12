CREATE TABLE media_uploads (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    session_id uuid NOT NULL,
    state text NOT NULL DEFAULT 'open' CHECK(state IN ('open','complete','cancelled')),
    final_parts integer CHECK(final_parts BETWEEN 1 AND 8192),
    whole_md5 text CHECK(whole_md5 ~ '^[0-9a-f]{32}$'),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX media_uploads_owner ON media_uploads(owner_id,state);
CREATE TABLE media_upload_parts (
    upload_id uuid NOT NULL REFERENCES media_uploads(id),
    part integer NOT NULL CHECK(part BETWEEN 0 AND 8191),
    size integer NOT NULL CHECK(size BETWEEN 1 AND 1048576),
    sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    storage_key uuid NOT NULL UNIQUE,
    PRIMARY KEY(upload_id,part)
);
