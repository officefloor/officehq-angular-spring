-- A hold is dated: it runs from its from-date to its to-date (both included), or on with no to-date,
-- and a person may have several over time. Only their open claims dated within a hold are left out
-- of the live totals. A person who was on hold keeps a hold from the beginning of time.
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE,
    CHECK (to_date IS NULL OR to_date >= from_date)
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, from_date, to_date) SELECT id, DATE '0001-01-01', NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
