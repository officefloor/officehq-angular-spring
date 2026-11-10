-- A claim due above the sheet's review threshold goes to REVIEW when opened, until a steward's
-- review makes it OPEN. The status's allowed values, on the claim and in its status log, gain
-- REVIEW, so each status moves to a column whose check allows it.
ALTER TABLE claim ADD COLUMN status_review VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_review IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
UPDATE claim SET status_review = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_review RENAME TO status;

ALTER TABLE claim_log ADD COLUMN status_review VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_review IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
UPDATE claim_log SET status_review = status;
ALTER TABLE claim_log DROP COLUMN status;
ALTER TABLE claim_log ALTER COLUMN status_review RENAME TO status;
ALTER TABLE claim_log ALTER COLUMN status DROP DEFAULT;
