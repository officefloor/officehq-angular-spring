-- The grades added to a sheet beyond A, B and C, comma separated in alphabetical order; null for none.
-- A grade is a code of one to three capital letters.
ALTER TABLE sheet ADD COLUMN added_grades VARCHAR(4000);
ALTER TABLE sheet ALTER COLUMN retired_grades SET DATA TYPE VARCHAR(4000);
ALTER TABLE sheet ALTER COLUMN default_grade SET DATA TYPE VARCHAR(3);
