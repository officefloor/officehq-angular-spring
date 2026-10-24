-- The holds a person has been put on, each from a date and, optionally, to one (both included): an
-- OPEN or PENDING claim of theirs dated within a hold counts in no live total, unless a statement
-- binds it. A person held before holds had dates keeps a hold from the beginning of time, with no end.
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    hold_from DATE   NOT NULL,
    hold_to   DATE
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, hold_from, hold_to) SELECT id, DATE '0001-01-01', NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
