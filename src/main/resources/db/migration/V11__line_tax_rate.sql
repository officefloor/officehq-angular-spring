-- A line's own tax percentage, 0 to 100 with up to two decimals; NULL means the line uses the workspace rate.
ALTER TABLE invoice_line ADD COLUMN tax_rate DECIMAL(5, 2);
