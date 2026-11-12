-- The grade the quick-add bar gives a new person and an imported line with a blank grade takes; null: A. Not dated.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
