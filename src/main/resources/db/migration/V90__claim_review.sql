-- A claim whose due is above the sheet's review threshold goes to REVIEW when opened, until a
-- steward reviews it (OPEN). The status column is rebuilt with a check that allows it.
ALTER TABLE claim ADD COLUMN status_with_review VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
    CHECK (status_with_review IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
UPDATE claim SET status_with_review = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_with_review RENAME TO status;
