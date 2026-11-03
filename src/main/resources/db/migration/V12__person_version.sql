-- A person's version: bumped on every save, so an edit made from a stale copy is refused.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
