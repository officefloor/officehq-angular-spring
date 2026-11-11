-- A person's snapshot lines: one per SNAPSHOT written for them when the sheet's today changed, with the day being
-- left and their due-total on it as the SNAPSHOT recorded it.
CREATE TABLE person_snapshot (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL,
    person_id    BIGINT       NOT NULL,
    snapshot_day DATE         NOT NULL,
    due_total    VARCHAR(40)  NOT NULL
);
CREATE INDEX person_snapshot_sheet ON person_snapshot (sheet_id);
CREATE INDEX person_snapshot_person ON person_snapshot (person_id);
