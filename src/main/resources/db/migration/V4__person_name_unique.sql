-- A person's name is unique on the sheet, compared without regard to case and ignoring spaces
-- around it. Names are kept trimmed; name_key is the comparison form the unique index holds.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(name));
CREATE UNIQUE INDEX person_sheet_name ON person (sheet_id, name_key);
