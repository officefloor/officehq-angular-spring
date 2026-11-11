-- A DRAFT claim may be DELETED: it counts nowhere, and on the sheet's today it was deleted it may be restored to
-- DRAFT. The status column is rebuilt to allow it.
ALTER TABLE claim ADD COLUMN status_new VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_new IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
UPDATE claim SET status_new = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_new RENAME TO status;
