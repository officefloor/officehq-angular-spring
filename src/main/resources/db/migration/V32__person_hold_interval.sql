-- A person's holds: each runs from its from-date to its to-date, inclusive (open-ended when it has none), and a
-- person may have several over time. A person held before keeps a hold from their earliest claim's date (the
-- sheet's today when earlier or they have none), open-ended.
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    hold_from DATE   NOT NULL,
    hold_to   DATE
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, hold_from, hold_to)
SELECT p.id, LEAST(s.today, COALESCE((SELECT MIN(c.claim_date) FROM claim c WHERE c.person_id = p.id), s.today)), NULL
FROM person p JOIN sheet s ON s.id = p.sheet_id
WHERE p.held;
ALTER TABLE person DROP COLUMN held;
