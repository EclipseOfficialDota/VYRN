-- Existing blocks have an unknown creation date; only new blocks get a timestamp.
ALTER TABLE blocks ADD COLUMN created_at timestamptz;
ALTER TABLE blocks ALTER COLUMN created_at SET DEFAULT now();
CREATE INDEX blocks_owner_date ON blocks(user_id,created_at DESC NULLS LAST,blocked_id);
