-- A person's place in the sheet's list: people are listed by it, new people go to the end.
ALTER TABLE person ADD COLUMN list_position BIGINT;
UPDATE person SET list_position = id;
ALTER TABLE person ALTER COLUMN list_position SET NOT NULL;
