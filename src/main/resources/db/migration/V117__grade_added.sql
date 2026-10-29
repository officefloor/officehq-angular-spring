-- Grades can be added to a sheet: a code of one to three capital letters. The grades added to the sheet, comma
-- separated in the order added, after the standing A, B and C; null (blank) for none. Not dated. Every grade column
-- now holds a code of up to three letters.
ALTER TABLE sheet ADD COLUMN added_grades VARCHAR(4000);
ALTER TABLE sheet ALTER COLUMN retired_grades VARCHAR(4000);
ALTER TABLE sheet ALTER COLUMN default_grade VARCHAR(3);
ALTER TABLE person_grade ALTER COLUMN grade VARCHAR(3) NOT NULL;
ALTER TABLE rate ALTER COLUMN grade VARCHAR(3) NOT NULL;
ALTER TABLE rate_change ALTER COLUMN grade VARCHAR(3) NOT NULL;
