-- A person's place in the sheet's list, counting from 1; people already on a sheet keep the order they were added.
ALTER TABLE person ADD COLUMN position INT;
UPDATE person p SET position = (SELECT COUNT(*) FROM person q WHERE q.sheet_id = p.sheet_id AND q.id <= p.id);
ALTER TABLE person ALTER COLUMN position SET NOT NULL;
CREATE INDEX person_sheet_position ON person (sheet_id, position);
