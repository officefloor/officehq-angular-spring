-- A DRAFT claim can be deleted (DELETED), and restored to DRAFT on the sheet-today it was deleted.
-- The status's allowed values, on the claim and in its status log, gain DELETED, so each status
-- moves to a column whose check allows it.
ALTER TABLE claim ADD COLUMN status_deleted VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_deleted IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
UPDATE claim SET status_deleted = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_deleted RENAME TO status;

ALTER TABLE claim_log ADD COLUMN status_deleted VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_deleted IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
UPDATE claim_log SET status_deleted = status;
ALTER TABLE claim_log DROP COLUMN status;
ALTER TABLE claim_log ALTER COLUMN status_deleted RENAME TO status;
ALTER TABLE claim_log ALTER COLUMN status DROP DEFAULT;
