-- A DRAFT claim may be DELETED: it counts nowhere, and on the sheet's today it was deleted it may
-- be restored to DRAFT. The claim's named CHECK is replaced; the status log's unnamed CHECK goes
-- with its column, which is rebuilt to allow DELETED.
ALTER TABLE claim DROP CONSTRAINT claim_status_known;
ALTER TABLE claim ADD CONSTRAINT claim_status_known
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
ALTER TABLE claim_log ADD COLUMN new_status VARCHAR(10);
UPDATE claim_log SET new_status = status;
ALTER TABLE claim_log DROP COLUMN status;
ALTER TABLE claim_log ALTER COLUMN new_status RENAME TO status;
ALTER TABLE claim_log ALTER COLUMN status SET NOT NULL;
ALTER TABLE claim_log ADD CONSTRAINT claim_log_status_known
    CHECK (status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
