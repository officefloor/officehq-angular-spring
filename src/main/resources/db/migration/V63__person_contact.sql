-- A person's contact: kept without surrounding spaces and in lower case, unique on the sheet; null
-- when they have none.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
