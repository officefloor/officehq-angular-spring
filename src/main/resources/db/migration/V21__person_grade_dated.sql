-- A person's grade is dated: each grade a person is given is kept with the date it applies from,
-- the first one (no from-date) from the beginning of time. The grade in force on a date is the one
-- with the latest from-date on or before it. from_key stands for the from-date (no from-date counting
-- as the earliest date) so a person has at most one grade per from-date.
CREATE TABLE person_grade (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT     NOT NULL REFERENCES person (id),
    grade     VARCHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    from_date DATE,
    from_key  DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'))
);
CREATE UNIQUE INDEX person_grade_from_unique ON person_grade (person_id, from_key);
INSERT INTO person_grade (person_id, grade) SELECT id, grade FROM person;
ALTER TABLE person DROP COLUMN grade;
