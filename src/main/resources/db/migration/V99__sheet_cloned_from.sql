-- The sheet a sheet was cloned from: a grade with no rate in force on it nor on its parents takes that sheet's.
ALTER TABLE sheet ADD COLUMN cloned_from VARCHAR(100);
