-- A claim a clerk settles with a due above the clerk limit becomes PENDING until a steward approves
-- it (SETTLED) or rejects it with a reason (back to OPEN). A PENDING claim counts as open in every
-- total. The status column is rebuilt so its check allows PENDING.
ALTER TABLE claim ADD COLUMN status_new VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
    CHECK (status_new IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
UPDATE claim SET status_new = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_new RENAME TO status;
