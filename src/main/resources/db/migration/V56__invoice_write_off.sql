-- A sent or part-paid invoice that will never be paid can be written off as bad debt. It stays on
-- record but is no longer owed.
ALTER TABLE invoice DROP CONSTRAINT invoice_status_valid;
ALTER TABLE invoice ADD CONSTRAINT invoice_status_valid
    CHECK (status IN ('DRAFT', 'SENT', 'PARTIAL', 'PAID', 'VOID', 'WRITTEN_OFF'));
