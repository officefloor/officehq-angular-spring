-- A person's contact: stored with surrounding spaces removed and lower-cased; null when they have
-- none. A contact is held by at most one person on the sheet.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
ALTER TABLE person ADD CONSTRAINT person_sheet_contact_unique UNIQUE (sheet_id, contact);
ALTER TABLE person_revision ADD COLUMN contact VARCHAR(200);
