-- A void reason on a sheet: trimmed text, on the sheet's list at most once. While the list is not
-- empty a claim is voided only with a reason from it, which the claim keeps (null: voided without one).
CREATE TABLE void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(100) NOT NULL
);
CREATE UNIQUE INDEX void_reason_sheet_reason ON void_reason (sheet_id, reason);
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(100);
