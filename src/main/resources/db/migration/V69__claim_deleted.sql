-- A draft claim may be DELETED (and restored to DRAFT on the same sheet-today). The status column
-- is rebuilt with a check that allows it.
ALTER TABLE claim ADD COLUMN status_with_deleted VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
    CHECK (status_with_deleted IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
UPDATE claim SET status_with_deleted = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_with_deleted RENAME TO status;
