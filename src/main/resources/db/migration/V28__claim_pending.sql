-- A clerk settling a claim whose due is above the clerk limit now puts it PENDING: a steward approves
-- it (SETTLED) or rejects it (back to OPEN), and it can be voided. The status check is re-made with it.
ALTER TABLE claim ALTER COLUMN status RENAME TO status_old;
ALTER TABLE claim ADD COLUMN status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
UPDATE claim SET status = status_old;
ALTER TABLE claim DROP COLUMN status_old;
