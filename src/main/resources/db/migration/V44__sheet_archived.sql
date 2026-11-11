-- A steward can archive a sheet: everything on it stays visible and every change to it is refused.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
