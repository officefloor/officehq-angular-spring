-- A claim whose due is above the sheet's review threshold (a dated setting, none when blank) in
-- force on its date goes to REVIEW when opened, unnumbered, for a steward to make OPEN. The claim's
-- and the status log's named CHECKs are replaced to allow it.
ALTER TABLE claim DROP CONSTRAINT claim_status_known;
ALTER TABLE claim ADD CONSTRAINT claim_status_known
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
ALTER TABLE claim_log DROP CONSTRAINT claim_log_status_known;
ALTER TABLE claim_log ADD CONSTRAINT claim_log_status_known
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
