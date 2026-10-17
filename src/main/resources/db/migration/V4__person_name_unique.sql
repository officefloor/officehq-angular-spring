-- A person's name is unique on its sheet, compared without regard to case. Names are stored with
-- surrounding spaces removed, so the key only needs to fold case.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(100) GENERATED ALWAYS AS (LOWER(name));
ALTER TABLE person ADD CONSTRAINT person_sheet_name UNIQUE (sheet_id, name_key);
