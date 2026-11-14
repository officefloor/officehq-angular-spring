-- The sheet a sheet was cloned from: a grade with no rate in force on a date on the sheet nor on its
-- parents takes that sheet's (then its parents', then the sheet it was cloned from, and so on). Null
-- is a sheet that was not cloned.
ALTER TABLE sheet ADD COLUMN cloned_from VARCHAR(200);
