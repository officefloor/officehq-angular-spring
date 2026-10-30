-- A person's contact: stored trimmed and lower-cased, null for none; unique on the sheet.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
ALTER TABLE person ADD CONSTRAINT person_contact_unique UNIQUE (sheet_id, contact);
