-- The version of a person, one more on every save: an edit carries the version it was loaded at
-- and is refused when someone else has saved the person since.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
