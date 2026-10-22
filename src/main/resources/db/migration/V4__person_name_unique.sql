-- A person's name is unique on the sheet, ignoring case and surrounding spaces. Names are kept
-- with surrounding spaces removed; name_key is the name lower-cased, the key the uniqueness is on.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(200);
UPDATE person SET name_key = LOWER(name);
ALTER TABLE person ALTER COLUMN name_key SET NOT NULL;
CREATE UNIQUE INDEX person_sheet_name ON person (sheet_id, name_key);
