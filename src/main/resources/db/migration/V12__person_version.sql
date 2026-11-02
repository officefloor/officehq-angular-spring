-- The version of a person, raised each time they are saved. An editor saves only over the version
-- it loaded, so a save that another user overtook is refused.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
