-- A DRAFT claim may now be DELETED: it counts nowhere, and may be restored to a DRAFT on the sheet's
-- today it was deleted.
ALTER TABLE claim ADD COLUMN next_status VARCHAR(16) DEFAULT 'DRAFT' NOT NULL
    CHECK (next_status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
UPDATE claim SET next_status = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN next_status RENAME TO status;
