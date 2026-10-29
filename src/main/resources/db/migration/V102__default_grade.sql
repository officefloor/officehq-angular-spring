-- The sheet's default grade: the grade the quick-add bar gives a new person and an import line with a blank grade
-- uses; null (blank) for A. Not dated.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
