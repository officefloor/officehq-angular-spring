-- An invoice can carry a minimum charge: when its net total (after the discounts, taxes and surcharge)
-- comes out under it, the minimum is billed instead. Existing invoices have none, so their amounts are
-- unchanged.
ALTER TABLE invoice ADD COLUMN minimum_charge DECIMAL(12, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_minimum_charge_non_negative CHECK (minimum_charge >= 0);
