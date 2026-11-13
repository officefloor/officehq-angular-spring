-- The sheet's default grade: the grade a person added by the quick-add bar, or by an import line with a
-- blank grade, takes. None means A. Not dated.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1) CHECK (default_grade IN ('A', 'B', 'C'));
