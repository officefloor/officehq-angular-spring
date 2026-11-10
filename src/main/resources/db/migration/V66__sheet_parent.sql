-- The sheet's parent (none: no parent): its rates stand in for a grade with no rate of its own in force.
ALTER TABLE sheet ADD COLUMN parent_id VARCHAR(100);
