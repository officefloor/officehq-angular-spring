-- A hold runs from its from-date to its to-date, both inclusive (no to-date: open-ended), and a person may have
-- several over time. Only the person's OPEN and PENDING claims dated within a hold, and not bound by a statement,
-- are left out of the live totals. A person held before keeps a hold from the beginning of time with no end.
CREATE TABLE person_hold (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE
);
INSERT INTO person_hold (person_id, from_date, to_date) SELECT id, DATE '0001-01-01', NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
