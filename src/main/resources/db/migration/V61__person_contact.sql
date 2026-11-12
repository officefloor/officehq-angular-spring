-- A person may have a contact, stored trimmed and lower-cased; none (null) when they have none. A
-- contact is unique on the sheet: the index refuses a second person with it.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
