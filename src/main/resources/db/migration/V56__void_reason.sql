-- The void reasons on a sheet, each once: while there are any, a claim is voided only with one of them.
CREATE TABLE sheet_void_reason (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    reason   VARCHAR(100) NOT NULL,
    CONSTRAINT sheet_void_reason_unique UNIQUE (sheet_id, reason)
);

-- The reason a claim was voided with; null when it is not VOID or was voided without one.
ALTER TABLE claim ADD COLUMN void_reason VARCHAR(100);
