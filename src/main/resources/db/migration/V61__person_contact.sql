-- A person's contact: kept trimmed and lower-cased, unique among the people on its sheet. Null: the person has none.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
