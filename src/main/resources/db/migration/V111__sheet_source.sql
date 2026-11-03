-- The id of the sheet a sheet was cloned from (null: it was not cloned): a grade with no rate in
-- force on a date on the sheet nor on its parents takes that sheet's rate.
ALTER TABLE sheet ADD COLUMN source_id VARCHAR(100);
