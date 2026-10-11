-- A tax-exempt line (postage, say) is left out of the taxed amount; lines are taxable by default.
ALTER TABLE invoice_line ADD COLUMN tax_exempt BOOLEAN DEFAULT FALSE NOT NULL;
