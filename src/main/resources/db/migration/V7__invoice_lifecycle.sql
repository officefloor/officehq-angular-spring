-- Invoices move through a lifecycle: DRAFT -> SENT -> PAID. New invoices start as drafts.
-- Existing unpaid invoices had already been issued, so they become SENT.
ALTER TABLE invoice DROP CONSTRAINT invoice_status_valid;
UPDATE invoice SET status = 'SENT' WHERE status = 'UNPAID';
ALTER TABLE invoice ALTER COLUMN status SET DEFAULT 'DRAFT';

ALTER TABLE invoice ADD CONSTRAINT invoice_status_valid CHECK (status IN ('DRAFT', 'SENT', 'PAID'));
