-- A person's contact, stored trimmed and lower-cased; null when they have none. A contact is unique
-- on the sheet (people with none do not clash).
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
