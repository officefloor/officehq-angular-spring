-- Whether a steward has archived the sheet: an archived sheet stays readable and refuses every change.
ALTER TABLE sheet ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
