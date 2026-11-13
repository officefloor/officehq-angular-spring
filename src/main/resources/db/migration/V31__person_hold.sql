-- A hold is dated: it runs from its from-date to its to-date (none: open-ended), and a person may
-- have several holds over time. Only a person's OPEN claims dated within one of their holds, and
-- not bound by a statement, are left out of the live totals. A person on hold before holds were
-- dated keeps a hold from the beginning of time with no end.
CREATE TABLE person_hold (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE NOT NULL,
    to_date DATE
);
CREATE INDEX person_hold_sheet ON person_hold (sheet_id, person_id);
INSERT INTO person_hold (sheet_id, person_id, from_date, to_date)
    SELECT sheet_id, id, DATE '0001-01-01', NULL FROM person WHERE held = TRUE;
ALTER TABLE person DROP COLUMN held;
