-- The version of a person, moved on by each save, so an editor opened on an older version is refused.
ALTER TABLE person ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
