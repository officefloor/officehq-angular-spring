-- A person may have several holds over time, each from a from-date to an optional to-date (null:
-- open-ended). Only their open claims dated within a hold, and not bound by a statement, are left
-- out of the live totals. A person held before holds had dates is held from the beginning of time
-- (a null from-date).
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE,
    to_date   DATE
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, from_date, to_date) SELECT id, NULL, NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
