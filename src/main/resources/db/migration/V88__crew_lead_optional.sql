-- A crew keeps its members when its lead leaves the sheet, and is then led by no one.
ALTER TABLE crew ALTER COLUMN lead_person_id SET NULL;
