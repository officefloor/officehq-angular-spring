-- A whole client can be tax exempt: none of their invoices carry any sales tax or levy, whatever the
-- lines say. Each invoice carries whether it is tax exempt (taken from its client), so a sent invoice
-- keeps the figures it was issued with. Existing clients and invoices are taxed as before, so no
-- amounts change.
ALTER TABLE client ADD COLUMN tax_exempt BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE invoice ADD COLUMN tax_exempt BOOLEAN DEFAULT FALSE NOT NULL;
