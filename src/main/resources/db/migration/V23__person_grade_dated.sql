-- A person's grade is dated: each grade they are given applies from its from-date until a later one
-- takes over; the first one they were given has none and applies from the beginning of time. At most
-- one grade per from-date; giving one again for that date replaces it.
CREATE TABLE person_grade (
    person_id BIGINT     NOT NULL REFERENCES person (id),
    grade     VARCHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    from_date DATE
);
CREATE UNIQUE INDEX person_grade_from ON person_grade (person_id, from_date);
INSERT INTO person_grade (person_id, grade, from_date) SELECT id, grade, NULL FROM person;
ALTER TABLE person DROP COLUMN grade;
