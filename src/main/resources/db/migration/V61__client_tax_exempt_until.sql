-- The last issue date a tax-exempt client's exemption covers: invoices issued after it are taxed as usual;
-- null for an exemption that does not end.
ALTER TABLE client ADD COLUMN tax_exempt_until DATE;
