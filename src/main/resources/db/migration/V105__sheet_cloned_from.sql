-- The sheet a sheet was cloned from: its rates (and those of its parents and source) apply where the
-- clone and its parents have none in force. Null when the sheet was not cloned.
ALTER TABLE sheet ADD COLUMN cloned_from VARCHAR(64);
