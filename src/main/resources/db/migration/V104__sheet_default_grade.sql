-- The sheet's default grade (none: A): the grade the quick-add bar gives a new person, and an import
-- line with a blank grade takes. Not dated; changing it changes no one.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
