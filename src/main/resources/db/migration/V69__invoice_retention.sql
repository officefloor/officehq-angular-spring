-- A percentage of an invoice may be held back as retention: part of the amount invoiced that is not
-- due yet. Existing invoices hold none back.
ALTER TABLE invoice ADD COLUMN retention_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_retention_pct_range CHECK (retention_pct >= 0 AND retention_pct <= 100);
