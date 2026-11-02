-- Each person's place in the sheet's list, smallest first; people already listed keep the order
-- they were added in.
ALTER TABLE person ADD COLUMN position BIGINT;
UPDATE person SET position = id;
ALTER TABLE person ALTER COLUMN position SET NOT NULL;
CREATE INDEX person_sheet_position ON person (sheet_id, position);
