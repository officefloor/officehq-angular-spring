-- The sheet's void reasons, each once; while any are on the list, a claim is voided with one of them.
CREATE TABLE void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX void_reason_unique ON void_reason (sheet_id, reason);
-- The reason a VOID claim was voided with; none when voided without one.
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(200);
