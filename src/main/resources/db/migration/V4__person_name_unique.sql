-- A person's name is unique on the sheet, compared without regard to case. Names are stored
-- trimmed; name_key is the name as compared, and the index refuses a second person with it.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(name));
CREATE UNIQUE INDEX person_sheet_name ON person (sheet_id, name_key);
