-- The sheet a sheet was cloned from: a grade with no rate in force on a date on the sheet nor on its parents
-- takes the source's, then the source's parents' and source's, and so on. None means not a clone. Not dated.
ALTER TABLE sheet ADD COLUMN source VARCHAR(200);
