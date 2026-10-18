-- A hold is dated: it runs from its from-date to its to-date (none: open-ended), both days in, and a
-- person may have several over time. Only their open claims dated within a hold, and not bound by a
-- statement, are left out of the live totals. A person held before this change keeps a hold from the
-- beginning of time (no from-date).
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE,
    to_date   DATE
);
INSERT INTO person_hold (person_id, from_date, to_date) SELECT id, NULL, NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
