-- Each SNAPSHOT written for a person: the day the sheet's today was leaving and their due-total
-- then, as shown ('n/a' when none).
CREATE TABLE person_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    snapshot_day DATE NOT NULL,
    due_total VARCHAR(40) NOT NULL
);
CREATE INDEX person_snapshot_sheet ON person_snapshot (sheet_id, person_id);
