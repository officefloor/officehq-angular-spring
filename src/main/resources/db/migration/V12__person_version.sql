-- The version of a person, raised on each save; an edit made from an older version is refused.
ALTER TABLE person ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
