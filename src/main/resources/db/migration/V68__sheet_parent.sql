-- A sheet's parent sheet, not dated: a grade with no rate in force on a date on the sheet takes the
-- parent's (then the parent's parent's, up to three levels up). Null is none.
ALTER TABLE sheet ADD COLUMN parent_id VARCHAR(200);
