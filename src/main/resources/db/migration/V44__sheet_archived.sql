-- Whether the sheet is archived: a steward archives it once, and from then on everything on it stays visible and
-- every change to it is refused.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
