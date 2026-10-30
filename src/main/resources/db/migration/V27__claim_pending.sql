-- A claim a clerk settles above the clerk limit waits as PENDING for a steward to approve or reject it.
ALTER TABLE claim DROP CONSTRAINT claim_status;
ALTER TABLE claim ADD CONSTRAINT claim_status CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
