-- A person's place in their sheet's list: the next on the sheet when they are added, swapped with a
-- neighbour's when they are moved up or down. Existing people keep the order they were added in.
ALTER TABLE person ADD COLUMN list_position BIGINT;
UPDATE person SET list_position = id;
ALTER TABLE person ALTER COLUMN list_position SET NOT NULL;
CREATE INDEX person_list_position ON person (sheet_id, list_position);
