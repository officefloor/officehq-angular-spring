-- The id of the sheet a sheet was cloned from, whose rates (and its parents' and source's) a grade with none in force
-- on the sheet nor on its parents falls back on; null: the sheet was not cloned.
ALTER TABLE sheet ADD COLUMN source VARCHAR(100);
