-- A percentage discount can be capped at a maximum amount, so a large invoice never has more than that
-- taken off by it. Existing discounts have no cap, so their amounts are unchanged.
ALTER TABLE invoice_discount ADD COLUMN discount_cap DECIMAL(12, 2);

ALTER TABLE invoice_discount ADD CONSTRAINT invoice_discount_cap_positive CHECK (discount_cap IS NULL OR discount_cap > 0);
ALTER TABLE invoice_discount ADD CONSTRAINT invoice_discount_cap_on_pct CHECK (discount_cap IS NULL OR discount_pct > 0);
