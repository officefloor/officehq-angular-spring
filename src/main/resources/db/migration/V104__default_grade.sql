-- The sheet's default grade (A, B or C; not dated; null means A): the grade the quick-add bar gives a new person,
-- and the one an imported line with a blank grade takes.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
