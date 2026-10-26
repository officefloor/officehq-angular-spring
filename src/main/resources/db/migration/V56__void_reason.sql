-- The sheet's void reasons, each at most once: while it has any, a claim is voided only with one of them.
CREATE TABLE void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(140) NOT NULL
);
CREATE UNIQUE INDEX void_reason_once ON void_reason (sheet_id, reason);

-- The reason a claim was voided with; null for one not voided, or voided without one.
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(140);
