-- An invoice can also take a flat amount off what its line items add up to, on top of (or instead of)
-- the percentage discount. It never takes off more than is left after the percentage. Existing
-- invoices have no flat discount, so their amounts are unchanged.
ALTER TABLE invoice ADD COLUMN discount_amount DECIMAL(12, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_discount_amount_non_negative CHECK (discount_amount >= 0);
