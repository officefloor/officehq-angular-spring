-- The version of a person, raised each time they are saved, so an editor that loaded an older
-- version is refused instead of overwriting another user's save.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
