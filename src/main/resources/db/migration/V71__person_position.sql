-- People have a position in the sheet's list, smallest first; existing people keep the order they were
-- added in (by id). New people go to the end.
ALTER TABLE person ADD COLUMN list_position BIGINT;
UPDATE person SET list_position = id;
ALTER TABLE person ALTER COLUMN list_position SET NOT NULL;
CREATE INDEX person_sheet_position ON person (sheet_id, list_position);
