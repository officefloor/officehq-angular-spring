-- An invoice may be billed in a currency other than its client's; when currency is null the invoice is in
-- the client's currency (and follows it if the client's currency changes).
ALTER TABLE invoice ADD COLUMN currency VARCHAR(3);
ALTER TABLE invoice ADD CONSTRAINT invoice_currency_fk FOREIGN KEY (currency) REFERENCES currency (code);

-- A share of a lump payment made against an invoice in another currency is converted into the invoice's
-- currency at the payment's date. The payment's amount is always in the invoice's currency; share_amount is
-- the share as taken out of the lump, in the lump's currency (null when the payment was not split from a lump).
ALTER TABLE payment ADD COLUMN share_amount DECIMAL(12, 2);
