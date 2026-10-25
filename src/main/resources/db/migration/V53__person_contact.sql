-- A person may have a contact, stored trimmed and lower-cased; none is null. A contact is unique on the sheet.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
