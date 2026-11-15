-- A person's place in the sheet's list: people are listed by it, lowest first. A new person goes
-- to the end; moving a person up or down swaps theirs with their neighbour's. Those already on a
-- sheet keep the order they were added in (by id).
ALTER TABLE person ADD COLUMN list_position BIGINT;
UPDATE person SET list_position = id;
ALTER TABLE person ALTER COLUMN list_position SET NOT NULL;
CREATE INDEX person_sheet_position ON person (sheet_id, list_position, id);
