-- An invoice can add a second tax, a levy, on top of the sales tax. Like the sales tax it is a
-- percentage worked out on the taxable base (the taxable lines after the discount), and the stored
-- amount now includes it as well; existing invoices have no levy, so their amounts are unchanged.
ALTER TABLE invoice ADD COLUMN levy_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT invoice_levy_pct_range CHECK (levy_pct >= 0 AND levy_pct <= 100);
