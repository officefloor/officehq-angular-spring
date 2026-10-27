-- A person has a position in their sheet's list, smallest first; the people already kept stay in the order they were
-- added. A new person goes to the end.
ALTER TABLE person ADD COLUMN list_position BIGINT;
UPDATE person SET list_position = id;
ALTER TABLE person ALTER COLUMN list_position SET NOT NULL;
CREATE INDEX person_sheet_position ON person (sheet_id, list_position);
