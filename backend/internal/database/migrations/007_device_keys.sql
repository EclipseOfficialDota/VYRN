-- Signal addresses identify a device generation, separately from its login UUID.
-- UUID addresses with Signal device number1 avoid recycling the library's1-127 slots.
ALTER TABLE device_keys ADD COLUMN signal_address_id uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX device_signal_address ON device_keys(signal_address_id);
ALTER TABLE device_keys ADD COLUMN kyber_prekey jsonb;
ALTER TABLE device_keys ADD COLUMN publication_hash bytea;
ALTER TABLE one_time_prekeys ADD COLUMN claimed_at timestamptz;
CREATE INDEX unclaimed_prekeys ON one_time_prekeys(user_id,device_id,key_id) WHERE claimed_at IS NULL;
-- Retain consumed IDs and record hashes: retried publication must never refill them.
CREATE TABLE device_key_history (
    user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    kind text NOT NULL CHECK(kind IN ('ec','signed','kyber')),
    key_id integer NOT NULL CHECK(key_id BETWEEN 0 AND 16777215),
    record_hash bytea NOT NULL CHECK(octet_length(record_hash)=32),
    PRIMARY KEY(user_id,device_id,kind,key_id),
    FOREIGN KEY(user_id,device_id) REFERENCES device_keys(user_id,device_id)
);
