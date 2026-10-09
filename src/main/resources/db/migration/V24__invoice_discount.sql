-- An invoice can take a percentage off what its line items add up to. The stored amount remains what
-- is owed, so it is now the subtotal less the discount; existing invoices have no discount.
ALTER TABLE invoice ADD COLUMN discount_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_discount_pct_range CHECK (discount_pct >= 0 AND discount_pct <= 100);
