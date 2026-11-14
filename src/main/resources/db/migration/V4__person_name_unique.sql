-- A person's name is unique on its sheet, ignoring case and surrounding spaces. Names are kept
-- trimmed; name_key is the lower-cased name the uniqueness is enforced on.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(name));
CREATE UNIQUE INDEX person_sheet_name ON person (sheet_id, name_key);
