-- A DRAFT claim can be DELETED: it counts nowhere, and on the same sheet-today it can be a DRAFT again.
ALTER TABLE claim DROP CONSTRAINT claim_status_known;
ALTER TABLE claim ADD CONSTRAINT claim_status_known
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
