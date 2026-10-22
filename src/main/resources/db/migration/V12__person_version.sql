-- The version of a person: raised on every save, so an editor that loaded an older version is refused.
ALTER TABLE person ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
