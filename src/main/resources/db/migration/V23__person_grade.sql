-- A person's grade is dated: each grade a person is given applies from its from-date, and the one in
-- force on a date is the one whose from-date is the latest on or before it. The first grade a person
-- was given has no from-date and applies from the beginning of time. A person has at most one grade
-- per from-date (no from-date counting as one).
CREATE TABLE person_grade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    grade CHAR(1) NOT NULL,
    from_date DATE,
    from_key DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'))
);
CREATE UNIQUE INDEX person_grade_person_from ON person_grade (person_id, from_key);
CREATE INDEX person_grade_sheet ON person_grade (sheet_id, person_id);
INSERT INTO person_grade (sheet_id, person_id, grade, from_date) SELECT sheet_id, id, grade, NULL FROM person;
ALTER TABLE person DROP COLUMN grade;
