-- A person's name is unique on the sheet, compared without regard to case, and stored without
-- surrounding spaces.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(name));
ALTER TABLE person ADD CONSTRAINT person_name_unique UNIQUE (sheet_id, name_key);
