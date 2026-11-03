-- A claim a clerk settles above the clerk limit goes to PENDING until a steward approves it
-- (SETTLED) or rejects it (back to OPEN). The status's allowed values gain PENDING, so the status
-- moves to a column whose check allows it.
ALTER TABLE claim ADD COLUMN status_pending VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_pending IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
UPDATE claim SET status_pending = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_pending RENAME TO status;
