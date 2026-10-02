CREATE TABLE cancelled_sends (
    sender_id uuid NOT NULL REFERENCES users(id),
    client_id uuid NOT NULL,
    conversation_id uuid NOT NULL REFERENCES conversations(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(sender_id, client_id)
);
