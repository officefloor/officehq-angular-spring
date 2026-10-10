-- A client can be pinned, so the office's favourite clients sit at the top of the client list.
-- Existing clients are not pinned.
ALTER TABLE client ADD COLUMN pinned BOOLEAN DEFAULT FALSE NOT NULL;
