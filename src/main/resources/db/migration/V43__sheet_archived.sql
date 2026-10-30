-- Whether a steward has archived the sheet: an archived sheet stays visible and every change to it is refused.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
