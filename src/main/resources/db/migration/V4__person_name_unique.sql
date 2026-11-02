-- A person's name is unique on the sheet, compared without regard to case and ignoring spaces
-- before and after it. Names are stored with those surrounding spaces removed.
UPDATE person SET name = TRIM(name);
ALTER TABLE person ADD COLUMN name_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(TRIM(name)));
ALTER TABLE person ADD CONSTRAINT person_sheet_name_unique UNIQUE (sheet_id, name_key);
