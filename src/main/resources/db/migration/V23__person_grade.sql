-- A person's grade is dated: each grade applies from its from-date until a later from-date of the
-- person takes over. The first grade a person was given has no from-date: it applies from the
-- beginning of time. A person has at most one grade per from-date (and one with none).
CREATE TABLE person_grade (
    person_id BIGINT     NOT NULL REFERENCES person (id),
    from_date DATE,
    grade     VARCHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C'))
);
CREATE INDEX person_grade_person ON person_grade (person_id);
INSERT INTO person_grade (person_id, from_date, grade) SELECT id, NULL, grade FROM person;
ALTER TABLE person DROP COLUMN grade;
