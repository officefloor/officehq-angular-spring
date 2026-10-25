-- The sheet's void reasons, each at most once. While a sheet has any, voiding a claim needs one of them.
CREATE TABLE void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(100) NOT NULL
);
CREATE UNIQUE INDEX void_reason_once ON void_reason (sheet_id, reason);
-- The reason a claim was voided with; null when it is not void or was voided with none.
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(100);
