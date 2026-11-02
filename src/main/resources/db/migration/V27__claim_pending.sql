-- A clerk's settle of a claim whose due is above the clerk limit leaves it PENDING until a steward
-- approves it (SETTLED) or rejects it (back to OPEN). The status column is rebuilt with a check
-- that allows it.
ALTER TABLE claim ADD COLUMN status_with_pending VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
    CHECK (status_with_pending IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
UPDATE claim SET status_with_pending = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_with_pending RENAME TO status;
