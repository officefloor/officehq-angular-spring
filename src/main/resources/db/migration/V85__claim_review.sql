-- A claim whose due is above the sheet's review threshold in force on its date goes to REVIEW when opened, waiting
-- for a steward to review it to OPEN; it counts nowhere until then. The status column is rebuilt to allow it.
ALTER TABLE claim ADD COLUMN status_new VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_new IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
UPDATE claim SET status_new = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_new RENAME TO status;
