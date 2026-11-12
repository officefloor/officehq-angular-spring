-- The sheet's parent: a grade with no rate in force on a date on this sheet takes the parent's, then the
-- parent's parent's, up to three levels up. None means no parent. Not dated.
ALTER TABLE sheet ADD COLUMN parent VARCHAR(200) REFERENCES sheet (id);
