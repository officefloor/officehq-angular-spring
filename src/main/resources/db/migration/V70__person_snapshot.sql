-- A person's due-total as it stood on a day the sheet's today left, one per SNAPSHOT written for them;
-- the id keeps the order they were taken. A null due-total is n/a. Kept by the person's id alone, so a
-- person merged or transferred away leaves theirs behind unseen.
CREATE TABLE person_snapshot (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    person_id BIGINT        NOT NULL,
    left_on   DATE          NOT NULL,
    due_total DECIMAL(12, 2)
);
CREATE INDEX person_snapshot_sheet ON person_snapshot (sheet_id, id);
