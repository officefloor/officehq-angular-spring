-- A person's contact, kept trimmed and lower-cased; null when they have none. No two people on a
-- sheet share one.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
