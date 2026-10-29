-- The sheet a sheet was cloned from, whose rates apply to a grade with none in force on it nor on its parents.
ALTER TABLE sheet ADD COLUMN cloned_from VARCHAR(100);
