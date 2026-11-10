-- A person's place in the sheet's list: people are listed by position (lowest first), new people
-- going to the end. Existing people keep the order they were added in.
ALTER TABLE person ADD COLUMN position BIGINT;
UPDATE person SET position = id;
ALTER TABLE person ALTER COLUMN position SET NOT NULL;
