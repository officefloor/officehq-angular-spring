-- An invoice can carry a surcharge: a flat amount, such as a handling fee, added to the total after the
-- discount and taxes (it is not itself taxed). Existing invoices have none, so their amounts are unchanged.
ALTER TABLE invoice ADD COLUMN surcharge DECIMAL(12, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_surcharge_non_negative CHECK (surcharge >= 0);
