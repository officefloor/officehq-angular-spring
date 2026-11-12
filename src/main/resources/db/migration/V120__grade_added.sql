-- The grades added to the sheet beyond A, B and C, comma separated in the order they were added; null: none. A grade
-- is a code of one to three capital letters. Not dated.
ALTER TABLE sheet ADD COLUMN added_grades VARCHAR(4000);
ALTER TABLE sheet ALTER COLUMN retired_grades SET DATA TYPE VARCHAR(4000);
ALTER TABLE sheet ALTER COLUMN default_grade SET DATA TYPE VARCHAR(3);
ALTER TABLE person_grade ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE rate ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
ALTER TABLE rate_log ALTER COLUMN grade SET DATA TYPE VARCHAR(3);
