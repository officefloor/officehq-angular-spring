-- A claim a clerk settles above the clerk limit is PENDING until a steward approves or rejects it.
ALTER TABLE claim DROP CONSTRAINT claim_status_known;
ALTER TABLE claim ADD CONSTRAINT claim_status_known CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
