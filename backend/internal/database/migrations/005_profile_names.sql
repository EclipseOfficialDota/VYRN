ALTER TABLE users ADD COLUMN first_name text CHECK (char_length(first_name) BETWEEN 1 AND 100);
ALTER TABLE users ADD COLUMN last_name text NOT NULL DEFAULT '' CHECK (char_length(last_name)<=100);
ALTER TABLE users ADD COLUMN about text NOT NULL DEFAULT '' CHECK (char_length(about)<=280);
