-- The reasons a claim can be voided with on a sheet, one row each; while a sheet has any, a void of
-- one of its claims names one of them, kept on the claim (null on a claim voided without one).
CREATE TABLE sheet_void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(200) NOT NULL,
    CONSTRAINT sheet_void_reason_unique UNIQUE (sheet_id, reason)
);
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(200);
