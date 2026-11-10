-- The sheet a sheet was cloned from (none: not a clone): its rates, and those of its parents and source, stand in
-- for a grade with no rate in force on the sheet nor on its parents.
ALTER TABLE sheet ADD COLUMN source_id VARCHAR(100);
