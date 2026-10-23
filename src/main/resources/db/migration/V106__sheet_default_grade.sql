-- The grade the quick-add bar and a blank-graded import line give a new person: A, B or C; null for A. Not dated.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
