-- A crew whose lead is transferred to another sheet stays, with no lead, its members kept.
ALTER TABLE crew ALTER COLUMN lead_person_id DROP NOT NULL;
