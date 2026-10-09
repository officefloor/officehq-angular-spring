-- A line item can be marked tax-free: some things charged for are not taxable. Existing lines are taxable.
ALTER TABLE invoice_line_item ADD COLUMN tax_exempt BOOLEAN NOT NULL DEFAULT FALSE;
