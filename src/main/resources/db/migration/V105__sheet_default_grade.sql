-- The grade a person added by the quick-add bar, or by an import line with a blank grade, is given;
-- null when it is A.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
