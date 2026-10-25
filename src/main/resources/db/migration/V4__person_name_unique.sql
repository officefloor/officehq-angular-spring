-- A person's name is unique on the sheet, compared without regard to case and ignoring surrounding
-- spaces. Names are stored trimmed; name_key is the trimmed, lower-cased name the uniqueness is kept on.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(200);
UPDATE person SET name_key = LOWER(name);
ALTER TABLE person ALTER COLUMN name_key SET NOT NULL;
CREATE UNIQUE INDEX person_sheet_name ON person (sheet_id, name_key);
