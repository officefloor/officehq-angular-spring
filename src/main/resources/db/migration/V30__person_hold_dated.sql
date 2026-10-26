-- A person's holds are dated: each row is a hold from its from-date to its to-date (null: with no end), and a person
-- may have several over time. Only their open claims dated within a hold, not bound by a statement, are left out of
-- the live totals. A person held before keeps a hold from the beginning of time.
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, from_date, to_date) SELECT id, DATE '0001-01-01', NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
