-- A claim a clerk settles above the clerk limit waits as PENDING for a steward to approve (SETTLED) or reject (back
-- to OPEN). PENDING claims count toward the totals like OPEN ones. The status column is rebuilt to allow it.
ALTER TABLE claim ADD COLUMN status_new VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_new IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
UPDATE claim SET status_new = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_new RENAME TO status;
