-- A crew keeps going when its lead leaves the sheet: it is then without a lead.
ALTER TABLE crew ALTER COLUMN lead_id DROP NOT NULL;
