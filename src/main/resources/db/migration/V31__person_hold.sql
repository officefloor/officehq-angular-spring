-- A person's holds, each from a date to an optional date (none: open-ended); a person may have several
-- over time. Only their OPEN claims dated within a hold, and not bound by a statement, count in no live
-- total. A person on hold before this keeps a hold from their earliest claim (or the sheet's today).
CREATE TABLE person_hold (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE,
    CHECK (to_date IS NULL OR to_date >= from_date)
);
CREATE INDEX person_hold_person ON person_hold (person_id);
INSERT INTO person_hold (person_id, from_date, to_date)
SELECT p.id, COALESCE((SELECT MIN(c.claim_date) FROM claim c WHERE c.person_id = p.id), s.today), NULL
FROM person p JOIN sheet s ON s.id = p.sheet_id WHERE p.held;
ALTER TABLE person DROP COLUMN held;
