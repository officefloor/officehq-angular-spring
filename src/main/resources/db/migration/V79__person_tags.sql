-- A person's tags: trimmed, lower-cased, without repeats, sorted and comma separated; null when
-- they have none. A revision keeps them too. Each is kept in sheet_tag as used on the sheet.
ALTER TABLE person ADD COLUMN tags VARCHAR(1000);
ALTER TABLE person_revision ADD COLUMN tags VARCHAR(1000);
