-- A crew's lead may be gone: transferring the lead to another sheet leaves the crew without one.
ALTER TABLE crew ALTER COLUMN lead_id SET NULL;
