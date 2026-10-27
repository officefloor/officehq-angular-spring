-- The sheet's parent sheet, whose rates (and its parent's, up to three levels up) apply to a grade with no rate in
-- force on a date on this sheet; null for none. Not dated.
ALTER TABLE sheet ADD COLUMN parent_id VARCHAR(100);
