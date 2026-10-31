-- Opening a claim whose due is above the sheet's review threshold makes it REVIEW, counting nowhere, until a
-- steward reviews it (OPEN); a REVIEW claim can be VOID.
ALTER TABLE claim DROP CONSTRAINT claim_status;
ALTER TABLE claim ADD CONSTRAINT claim_status CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
