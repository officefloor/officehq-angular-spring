-- A crew outlives its lead leaving the sheet: it is kept, with no lead, and its members.
ALTER TABLE sheet_crew ALTER COLUMN lead_person_id SET NULL;
