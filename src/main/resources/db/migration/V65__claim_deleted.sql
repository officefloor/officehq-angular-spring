-- A DRAFT claim can be DELETED: it counts nowhere, and on the sheet's today it was deleted it can be
-- restored to a DRAFT.
ALTER TABLE claim DROP CONSTRAINT claim_status;
ALTER TABLE claim ADD CONSTRAINT claim_status CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
