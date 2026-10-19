-- The version of a person, raised each time the person is saved, so an edit made from an editor
-- that loaded an older version is refused.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
