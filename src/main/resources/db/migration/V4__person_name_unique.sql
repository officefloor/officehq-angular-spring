-- A person's name is unique on its sheet, compared without regard to case and ignoring the spaces
-- around it. Names are stored with those surrounding spaces removed; name_key is the name as compared.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(name));
CREATE UNIQUE INDEX person_sheet_name ON person (sheet_id, name_key);
