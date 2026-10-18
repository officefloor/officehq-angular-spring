-- A person's version: moved on by every save, so an edit made from an older version is refused.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
