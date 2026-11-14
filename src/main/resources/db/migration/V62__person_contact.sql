-- A person's contact, kept trimmed and lower-cased; null for none. No two people on a sheet share one.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
ALTER TABLE person_history ADD COLUMN contact VARCHAR(200);
