-- A crew whose lead leaves the sheet stays on it, with its members, and no lead.
ALTER TABLE crew ALTER COLUMN lead_id SET NULL;
