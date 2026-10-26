-- A claim whose due is above the sheet's review threshold goes to REVIEW when opened, and a steward's
-- review opens it. A REVIEW claim counts nowhere. The status's check has no name to drop it by, so the
-- status moves to a new column with the wider check.
ALTER TABLE claim ADD COLUMN claim_status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (claim_status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
UPDATE claim SET claim_status = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN claim_status RENAME TO status;
