-- A payment may be made in a currency other than its invoice's; it is converted into the invoice's currency at
-- the rates in effect on the payment's date. The payment's amount stays in the invoice's currency (what it
-- settles); paid_currency and paid_amount record what was actually received (both null when paid in the
-- invoice's own currency).
ALTER TABLE payment ADD COLUMN paid_currency VARCHAR(3);
ALTER TABLE payment ADD COLUMN paid_amount DECIMAL(12, 2);
ALTER TABLE payment ADD CONSTRAINT payment_paid_currency_fk FOREIGN KEY (paid_currency) REFERENCES currency (code);
ALTER TABLE payment ADD CONSTRAINT payment_paid_both CHECK ((paid_currency IS NULL) = (paid_amount IS NULL));
ALTER TABLE payment ADD CONSTRAINT payment_paid_amount_positive CHECK (paid_amount IS NULL OR paid_amount > 0);
