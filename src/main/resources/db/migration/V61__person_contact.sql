-- A person may have a contact, stored without surrounding spaces and lower-cased, unique on its sheet. Null: none.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
CREATE UNIQUE INDEX person_sheet_contact ON person (sheet_id, contact);
