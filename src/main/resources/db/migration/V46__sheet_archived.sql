-- Whether the sheet is archived: once it is, every change asked of it is refused.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
