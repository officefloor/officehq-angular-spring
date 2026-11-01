-- A claim opened with a due above the review threshold in force on its date goes to REVIEW: it has
-- no number and counts nowhere until a steward's review opens it; it can be voided.
ALTER TABLE claim DROP CONSTRAINT claim_status_check;
ALTER TABLE claim ADD CONSTRAINT claim_status_check
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
