-- The sheet's parent: a grade with no rate in force on a date on this sheet takes the parent's rate
-- for that grade and date, then the parent's parent's, up to three levels up; null (blank) is none.
ALTER TABLE sheet ADD COLUMN parent_id VARCHAR(100);
