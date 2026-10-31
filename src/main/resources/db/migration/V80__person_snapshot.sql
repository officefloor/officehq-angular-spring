-- A person's snapshot lines: each change of the sheet's today records, for each person, the day being left and
-- their due-total on it ('n/a' when their grade had no rate then), as the SNAPSHOT audit line has it.
CREATE TABLE person_snapshot (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    left_on   DATE         NOT NULL,
    due_total VARCHAR(20)  NOT NULL
);
CREATE INDEX person_snapshot_person ON person_snapshot (sheet_id, person_id, left_on, id);
