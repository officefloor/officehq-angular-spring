-- The version of a person's values: raised on every save, so an editor that loaded an older
-- version is refused instead of overwriting what someone else saved in the meantime.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
