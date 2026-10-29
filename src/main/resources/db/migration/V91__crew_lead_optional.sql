-- A crew whose lead leaves the sheet stays, with no lead: its members carry on as the crew.
ALTER TABLE crew ALTER COLUMN lead_id SET NULL;
