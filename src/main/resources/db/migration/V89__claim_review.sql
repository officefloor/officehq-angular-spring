-- A claim whose due is above the sheet's review threshold in force on its date goes to REVIEW when
-- opened, until a steward reviews it (OPEN).
ALTER TABLE claim DROP CONSTRAINT claim_status_known;
ALTER TABLE claim ADD CONSTRAINT claim_status_known
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
