-- Telegram's UI expects positive, increasing integer message IDs. UUIDs remain
-- the public identity; ordinal gives every device the same stable ordering.
CREATE SEQUENCE message_ordinal_seq AS integer MINVALUE 1 NO CYCLE;
ALTER TABLE messages ADD COLUMN ordinal integer;
WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at,id)::integer AS ordinal FROM messages
)
UPDATE messages SET ordinal=numbered.ordinal FROM numbered WHERE messages.id=numbered.id;
SELECT setval('message_ordinal_seq', COALESCE((SELECT max(ordinal) FROM messages),1), EXISTS(SELECT 1 FROM messages));
ALTER SEQUENCE message_ordinal_seq OWNED BY messages.ordinal;
ALTER TABLE messages ALTER COLUMN ordinal SET NOT NULL;
ALTER TABLE messages ALTER COLUMN ordinal SET DEFAULT nextval('message_ordinal_seq');
ALTER TABLE messages ADD CONSTRAINT messages_ordinal_unique UNIQUE(ordinal);
ALTER TABLE messages ADD CONSTRAINT messages_ordinal_positive CHECK(ordinal>0);
CREATE INDEX messages_ordered_page ON messages(conversation_id,ordinal DESC);
