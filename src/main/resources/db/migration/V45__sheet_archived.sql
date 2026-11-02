-- Whether a steward has archived the sheet: an archived sheet stays visible and refuses every change.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
