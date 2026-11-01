-- A DRAFT claim may be DELETED: it counts nowhere and cannot change, unless restored to DRAFT on the
-- same sheet's today it was deleted.
ALTER TABLE claim DROP CONSTRAINT claim_status_check;
ALTER TABLE claim ADD CONSTRAINT claim_status_check
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
