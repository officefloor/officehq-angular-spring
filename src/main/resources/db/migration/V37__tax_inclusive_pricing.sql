-- A client's prices can already include tax. For such a client the sales tax and levy are worked back
-- out of the taxable lines instead of being added on top, so the invoice total is the discounted
-- subtotal. Each invoice carries whether it is priced tax-inclusive (taken from its client), so a
-- sent invoice keeps the figures it was issued with. Existing clients and invoices add tax on top,
-- as before, so no amounts change.
ALTER TABLE client ADD COLUMN tax_inclusive BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE invoice ADD COLUMN tax_inclusive BOOLEAN DEFAULT FALSE NOT NULL;
