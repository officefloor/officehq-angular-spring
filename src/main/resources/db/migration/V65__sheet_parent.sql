-- The sheet's parent sheet: a grade with no rate in force on a date on this sheet takes its parent's rate for that
-- grade and date, then the parent's parent's, up to three levels up. None means no parent. Not dated.
ALTER TABLE sheet ADD COLUMN parent_id VARCHAR(100);
