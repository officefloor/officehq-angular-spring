-- A claim a clerk settles above the clerk limit waits for a steward as PENDING: approved it becomes
-- SETTLED, rejected it is OPEN again.
ALTER TABLE claim DROP CONSTRAINT claim_status;
ALTER TABLE claim ADD CONSTRAINT claim_status CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
