-- The grades a person has been given, each from a date: the one with the latest from-date on or
-- before a date is in force then; the first one given (no from-date) applies from the beginning of time.
CREATE TABLE person_grade (
    person_id  BIGINT     NOT NULL REFERENCES person (id),
    grade      VARCHAR(1) NOT NULL,
    grade_from DATE
);
CREATE INDEX person_grade_person ON person_grade (person_id);
INSERT INTO person_grade (person_id, grade, grade_from) SELECT id, grade, NULL FROM person;
ALTER TABLE person DROP COLUMN grade;
