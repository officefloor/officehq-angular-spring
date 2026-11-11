-- The sheet's parent sheet: a grade with no rate in force on a date on this sheet takes the parent's for that date,
-- then the parent's parent's, up to three levels up. Not dated; null means none.
ALTER TABLE sheet ADD COLUMN parent VARCHAR(100);
