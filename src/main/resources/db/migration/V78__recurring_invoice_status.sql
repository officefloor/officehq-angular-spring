-- A recurring invoice may be paused so it stops generating, and resumed later.
ALTER TABLE recurring_invoice ADD COLUMN status VARCHAR(10) DEFAULT 'ACTIVE' NOT NULL;
ALTER TABLE recurring_invoice ADD CONSTRAINT recurring_invoice_status_known CHECK (status IN ('ACTIVE', 'PAUSED'));
