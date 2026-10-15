-- A person's grade: LOW, MID or HIGH; absent until given.
ALTER TABLE person ADD COLUMN grade VARCHAR(4);
ALTER TABLE person ADD CONSTRAINT person_grade_check CHECK (grade IN ('LOW', 'MID', 'HIGH'));
