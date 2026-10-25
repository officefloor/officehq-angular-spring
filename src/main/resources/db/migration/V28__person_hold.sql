-- A person's holds are dated: each runs from its from-date to its to-date (none: open-ended), and a
-- person may have several over time. Only their open claims dated within a hold, and not bound by a
-- statement, are left out of the live totals. A person held before this has a hold from the
-- beginning of time (no from-date), open-ended.
CREATE TABLE person_hold (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE,
    to_date   DATE
);
INSERT INTO person_hold (person_id) SELECT id FROM person WHERE held;
ALTER TABLE person DROP COLUMN held;
