-- An archived sheet: everything stays visible and every change is refused. Set by a steward, never undone.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
