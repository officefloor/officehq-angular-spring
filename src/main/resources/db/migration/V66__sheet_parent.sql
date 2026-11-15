-- The sheet's parent: the id of another sheet whose rates (then its parent's, up to three levels
-- up) apply to a grade with no rate of its own in force on a date; null when blank (none). Not dated.
ALTER TABLE sheet ADD COLUMN parent VARCHAR(100);
