-- A DRAFT claim can be deleted (DELETED), counting nowhere, and restored to DRAFT on the sheet's today it
-- was deleted. The status check is re-made with it.
ALTER TABLE claim ALTER COLUMN status RENAME TO status_old;
ALTER TABLE claim ADD COLUMN status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
UPDATE claim SET status = status_old;
ALTER TABLE claim DROP COLUMN status_old;
