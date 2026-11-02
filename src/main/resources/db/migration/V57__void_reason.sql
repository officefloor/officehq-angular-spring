-- The void reasons of a sheet, each (trimmed) text at most once. While a sheet has any, a claim is
-- voided with one of them, kept on the claim; null for a claim voided without one.
CREATE TABLE void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(200)  NOT NULL,
    added_on DATE          NOT NULL,
    CONSTRAINT void_reason_unique UNIQUE (sheet_id, reason)
);
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(200);
