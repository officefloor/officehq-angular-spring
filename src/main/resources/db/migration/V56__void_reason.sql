-- The void reasons of a sheet, each at most once: while the list is not empty, voiding a claim needs one of them.
CREATE TABLE void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(255) NOT NULL
);
CREATE UNIQUE INDEX void_reason_sheet_reason ON void_reason (sheet_id, reason);

-- The reason a claim was voided with; null when it was voided without one, or is not VOID.
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(255);
