-- A clerk settling a claim whose due is above the clerk limit sends it to PENDING, where a steward
-- approves it (SETTLED) or rejects it (back to OPEN). The status's unnamed CHECK goes with its
-- column, which is rebuilt to allow PENDING. A PENDING claim counts like an OPEN one.
ALTER TABLE claim ADD COLUMN new_status VARCHAR(10);
UPDATE claim SET new_status = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN new_status RENAME TO status;
ALTER TABLE claim ALTER COLUMN status SET DEFAULT 'DRAFT';
ALTER TABLE claim ALTER COLUMN status SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_status_known
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
