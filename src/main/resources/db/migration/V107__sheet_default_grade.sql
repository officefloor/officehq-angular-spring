-- A sheet's default grade, A, B or C, not dated: a person added by a quick-add, or by an import line
-- with a blank grade, is given it. Null (blank) stands for A.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
