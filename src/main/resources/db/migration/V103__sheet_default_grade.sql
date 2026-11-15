-- The grade a person the quick-add bar adds, or an import line with a blank grade, is given
-- (null: A). Not dated.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
