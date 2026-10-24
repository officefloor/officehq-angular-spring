-- The sheet's parent: the sheet whose rates (then its parent's, up to three levels up) are used for a
-- grade and date that have no rate in force on this sheet; null when there is none. Not dated.
ALTER TABLE sheet ADD COLUMN parent_id VARCHAR(100);
