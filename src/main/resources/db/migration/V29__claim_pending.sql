-- A clerk's settle of a claim whose due is above the clerk limit leaves it PENDING until a steward
-- approves it (SETTLED) or rejects it (back to OPEN). A PENDING claim counts like an OPEN one.
ALTER TABLE claim DROP CONSTRAINT claim_status;
ALTER TABLE claim ADD CONSTRAINT claim_status CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
