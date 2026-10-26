-- A person's grade is dated: each row records a grade applying from its from-date, a row with no from-date applying
-- from the beginning of time (the first grade the person was given). The grade in force on a date is the one with
-- the latest from-date on or before it. A person has at most one grade per from-date (the key folds "no from-date"
-- into one value).
CREATE TABLE person_grade (
    person_id BIGINT     NOT NULL REFERENCES person (id),
    grade     VARCHAR(1) NOT NULL,
    from_date DATE,
    from_key  DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'))
);
CREATE UNIQUE INDEX person_grade_from ON person_grade (person_id, from_key);
INSERT INTO person_grade (person_id, grade, from_date) SELECT id, grade, NULL FROM person;
ALTER TABLE person DROP COLUMN grade;
