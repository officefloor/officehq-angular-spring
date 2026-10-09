-- A sent invoice can be cancelled (voided) when it was sent by mistake. It stays on record but is no
-- longer owed.
ALTER TABLE invoice DROP CONSTRAINT invoice_status_valid;
ALTER TABLE invoice ADD CONSTRAINT invoice_status_valid
    CHECK (status IN ('DRAFT', 'SENT', 'PARTIAL', 'PAID', 'VOID'));
