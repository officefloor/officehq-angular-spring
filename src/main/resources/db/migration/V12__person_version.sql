-- The version of a person, raised on every save; an editor saves only the version it loaded.
ALTER TABLE person ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
