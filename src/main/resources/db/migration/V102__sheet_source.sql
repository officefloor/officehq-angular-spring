-- The id of the sheet a sheet was cloned from (null: not a clone): a grade with no rate in force
-- on a date on the sheet nor on its parents takes the source's (then its parents' and source's).
ALTER TABLE sheet ADD COLUMN source VARCHAR(100);
