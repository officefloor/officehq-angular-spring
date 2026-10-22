-- A hold has a from-date and an optional to-date (null: open-ended), and a person may have several
-- over time. Only their OPEN and PENDING claims dated within a hold, and not bound by a statement,
-- are left out of the live totals. A person on hold before this kept it for all dates: their hold
-- now runs from the earliest date there is.
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE,
    CHECK (to_date IS NULL OR to_date >= from_date)
);
INSERT INTO person_hold (person_id, from_date, to_date)
    SELECT id, DATE '0001-01-01', NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
