-- A client's own payment terms in days; NULL means invoices are due at the end of the following month.
ALTER TABLE client ADD COLUMN terms_days INT;
