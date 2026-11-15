-- Whether a steward has archived the sheet: everything on it stays visible and every change is refused.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
