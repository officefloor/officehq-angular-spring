-- A person's grades, each from a date: the one whose from-date is the latest on or before a date is in
-- force then; the first grade they were given has no from-date and applies from the beginning of time.
CREATE TABLE person_grade (
    person_id BIGINT     NOT NULL REFERENCES person (id),
    grade     VARCHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    from_date DATE
);
CREATE INDEX person_grade_person ON person_grade (person_id);
INSERT INTO person_grade (person_id, grade, from_date) SELECT id, grade, NULL FROM person;
ALTER TABLE person DROP COLUMN grade;
