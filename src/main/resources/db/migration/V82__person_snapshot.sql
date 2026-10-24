-- The SNAPSHOT lines written for a person as the sheet's today moves on: the day being left and their
-- due-total on it (a figure, or n/a), in the order they were written. Not tied to the person row, so
-- a person merged or transferred away leaves theirs behind unseen.
CREATE TABLE person_snapshot (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id    BIGINT       NOT NULL,
    snapshot_day DATE         NOT NULL,
    due_total    VARCHAR(20)  NOT NULL
);
CREATE INDEX person_snapshot_sheet ON person_snapshot (sheet_id, id);
