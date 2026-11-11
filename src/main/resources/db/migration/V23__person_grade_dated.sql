-- A person's grade is dated: each grade applies from its from-date on, until the person's next
-- later-dated grade takes over. The first grade a person was given has no from-date and applies
-- from the beginning of time. A person has at most one grade per from-date (and at most one with
-- none); from_key stands in for the missing from-date so the unique index covers that one too.
CREATE TABLE person_grade (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT     NOT NULL REFERENCES person (id),
    grade     VARCHAR(1) NOT NULL,
    from_date DATE,
    from_key  DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'))
);
CREATE UNIQUE INDEX person_grade_person_from ON person_grade (person_id, from_key);
INSERT INTO person_grade (person_id, grade, from_date) SELECT id, grade, NULL FROM person;
ALTER TABLE person DROP COLUMN grade;
