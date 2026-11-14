-- A hold has a from-date and an optional to-date (null: open-ended), and a person may have several
-- over time. Only their OPEN claims dated within a hold (and not bound by a statement) are left out
-- of the live totals. A person held before keeps a hold from the beginning of time.
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    position  INT    NOT NULL,
    from_date DATE   NOT NULL,
    to_date   DATE,
    CHECK (to_date IS NULL OR to_date >= from_date),
    PRIMARY KEY (person_id, position)
);
INSERT INTO person_hold (person_id, position, from_date, to_date)
    SELECT id, 0, DATE '0001-01-01', NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
