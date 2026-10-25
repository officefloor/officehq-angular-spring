-- A DRAFT claim can be deleted: it is DELETED, counts nowhere and can only be restored (a DRAFT again)
-- on the sheet's today it was deleted, as its status log records it. The status's check has no name to
-- drop it by, so the status moves to a new column with the wider check.
ALTER TABLE claim ADD COLUMN claim_status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (claim_status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
UPDATE claim SET claim_status = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN claim_status RENAME TO status;
