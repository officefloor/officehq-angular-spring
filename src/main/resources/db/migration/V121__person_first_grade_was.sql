-- A grade merge is retroactive: it gives a person's first grade (in force from the beginning of time)
-- the other grade. The first grade they were given is kept for what was recorded before that, and the
-- change is logged in person_grade_log with the from-date 0001-01-01, standing for the beginning of time.
ALTER TABLE person ADD COLUMN first_grade_was VARCHAR(3);
