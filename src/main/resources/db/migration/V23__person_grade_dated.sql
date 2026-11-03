-- A person's grades, each from a from-date (none: from the beginning of time) until their next
-- grade. The grade a person has so far applies from the beginning of time.
CREATE TABLE person_grade (
    person_id BIGINT     NOT NULL REFERENCES person (id),
    from_date DATE,
    grade     VARCHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C'))
);
CREATE INDEX person_grade_person ON person_grade (person_id);
INSERT INTO person_grade (person_id, from_date, grade) SELECT id, NULL, grade FROM person;
ALTER TABLE person DROP COLUMN grade;
