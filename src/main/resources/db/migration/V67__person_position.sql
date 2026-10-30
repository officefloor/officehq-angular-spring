-- A person's position in the sheet's list, counting from 1; people already on a sheet keep the order they
-- were added in. A new person goes to the end; moving a person swaps them with their neighbour.
ALTER TABLE person ADD COLUMN list_position INT NOT NULL DEFAULT 0;
UPDATE person p SET list_position = (SELECT COUNT(*) FROM person q WHERE q.sheet_id = p.sheet_id AND q.id <= p.id);
