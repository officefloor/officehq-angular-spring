-- An invoice can add a percentage sales tax on top of what is owed after any discount. The stored
-- amount remains what is owed, so it is now the discounted subtotal plus the tax; existing invoices
-- have no tax.
ALTER TABLE invoice ADD COLUMN tax_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_tax_pct_range CHECK (tax_pct >= 0 AND tax_pct <= 100);
