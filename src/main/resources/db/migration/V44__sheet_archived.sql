-- A sheet a steward has archived: everything on it stays visible and every change to it is refused.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
