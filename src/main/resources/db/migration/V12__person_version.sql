-- The version of a person, raised on every save; an edit names the version it loaded and is
-- refused when someone else has saved the person since.
ALTER TABLE person ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
