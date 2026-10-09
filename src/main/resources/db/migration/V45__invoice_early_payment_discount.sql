-- An invoice can offer an early-payment discount: a percentage off what is owed if it is paid within a
-- set number of days of being issued. Existing invoices offer none.
ALTER TABLE invoice ADD COLUMN early_payment_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
ALTER TABLE invoice ADD COLUMN early_payment_days INT DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_early_payment_pct_range CHECK (early_payment_pct >= 0 AND early_payment_pct <= 100);
ALTER TABLE invoice ADD CONSTRAINT invoice_early_payment_days_non_negative CHECK (early_payment_days >= 0);
