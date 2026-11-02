-- A person's holds are dated: each applies from its from-date to its to-date (open-ended when it
-- has none), and a person may have several over time. While a hold covers a claim's date, the
-- person's open claim of that date (one not bound by a statement) counts in no live total. A person
-- on hold before holds were dated keeps a hold from the beginning of time (no from-date).
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE,
    to_date   DATE
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, from_date, to_date) SELECT id, NULL, NULL FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
