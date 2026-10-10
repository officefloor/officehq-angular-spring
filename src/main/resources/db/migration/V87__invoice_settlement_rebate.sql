-- An invoice can offer a settlement rebate: a percentage of the amount given back when the client pays
-- before the due date. Existing invoices offer none.
ALTER TABLE invoice ADD COLUMN rebate_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_rebate_pct_range CHECK (rebate_pct >= 0 AND rebate_pct <= 100);
