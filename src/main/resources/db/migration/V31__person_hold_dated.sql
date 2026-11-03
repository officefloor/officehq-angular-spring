-- A person may have several holds over time, each from a from-date to an optional to-date (none:
-- open-ended), both inclusive. A person held so far is held from the beginning of time, open-ended.
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, from_date, to_date) SELECT id, DATE '0001-01-01', NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
