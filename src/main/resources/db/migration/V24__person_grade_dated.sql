-- A person's grades, each in force from its from-date until the next; the first one a person was given has no
-- from-date and applies from the beginning of time.
CREATE TABLE person_grade (
    person_id  BIGINT     NOT NULL REFERENCES person (id),
    grade      VARCHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    grade_from DATE
);
CREATE INDEX person_grade_person ON person_grade (person_id);
INSERT INTO person_grade (person_id, grade, grade_from) SELECT id, grade, NULL FROM person;
ALTER TABLE person DROP COLUMN grade;
