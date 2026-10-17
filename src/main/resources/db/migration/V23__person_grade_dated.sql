-- A person's grade is dated: each grade recorded applies from its from-date, the first one (with no
-- from-date) from the beginning of time. A person has at most one grade per from-date, which the
-- service ensures. The grade each person had becomes their first grade.
CREATE TABLE person_grade (
    person_id BIGINT  NOT NULL REFERENCES person (id),
    from_date DATE,
    grade     CHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C'))
);
CREATE INDEX person_grade_person ON person_grade (person_id);
INSERT INTO person_grade (person_id, from_date, grade) SELECT id, NULL, grade FROM person;
ALTER TABLE person DROP COLUMN grade;
