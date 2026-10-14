ALTER TABLE media ADD COLUMN duration_ms bigint NOT NULL DEFAULT 0;
ALTER TABLE media ADD CONSTRAINT media_duration_valid CHECK (
    duration_ms >= 0 AND (duration_ms = 0 OR (kind = 'voice' AND NOT encrypted))
);
