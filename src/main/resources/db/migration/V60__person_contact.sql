-- A person's contact: kept with surrounding spaces removed and lower-cased; null when they have
-- none. A contact is held by at most one person on the sheet.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
