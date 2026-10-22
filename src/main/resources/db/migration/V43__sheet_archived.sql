-- Archived: once a steward archives a sheet, everything on it stays visible but nothing on it may change.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
