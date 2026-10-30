-- A claim whose due is above the sheet's review threshold in force on its date goes to REVIEW when opened,
-- unnumbered and counting nowhere, until a steward reviews it (OPEN, numbered then); it can be VOID.
ALTER TABLE claim DROP CONSTRAINT claim_status;
ALTER TABLE claim ADD CONSTRAINT claim_status CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
