-- A steward may archive a sheet: it stays visible, and every change on it is refused.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
