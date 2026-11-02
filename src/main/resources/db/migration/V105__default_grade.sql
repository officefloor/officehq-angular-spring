-- The sheet's default grade (not dated): the grade quick-add gives a person it adds, and an import line
-- with a blank grade takes. Null means A.
ALTER TABLE sheet ADD COLUMN default_grade VARCHAR(1);
