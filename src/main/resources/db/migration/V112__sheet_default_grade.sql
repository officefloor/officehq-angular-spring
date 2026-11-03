-- The sheet's default grade: the grade a person added by the quick-add bar, or by an import line
-- with a blank grade, is given; null (blank) is A. Not dated.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
