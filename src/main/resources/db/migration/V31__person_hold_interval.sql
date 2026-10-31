-- A hold has a from-date and an optional to-date (none: it does not end), and a person may have several
-- holds over time. Only a person's OPEN claims dated within a hold, and not bound by a statement, are left
-- out of the live totals. A person held before keeps a hold from the beginning of their sheet's time.
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, from_date, to_date) SELECT id, DATE '0001-01-01', NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
