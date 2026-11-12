-- A crew keeps going when its lead is transferred to another sheet: it is then without a lead.
ALTER TABLE crew ALTER COLUMN lead_person_id SET NULL;
