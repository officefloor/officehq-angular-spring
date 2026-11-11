-- The version of a person, raised by one each time the person is saved. An editor sends back the
-- version it loaded; a save of a version someone else has since replaced is refused.
ALTER TABLE person ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
