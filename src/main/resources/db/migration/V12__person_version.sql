-- The version of a person, moved on by each save. An edit names the version it was made from and
-- is refused when someone else has saved the person since.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
