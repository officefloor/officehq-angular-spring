-- The currency a payment was paid in and the amount paid in it, when not the client's own; the
-- payment's amount is then that amount converted into the client's currency, which is what is applied.
ALTER TABLE payment ADD COLUMN paid_currency VARCHAR(3);
ALTER TABLE payment ADD COLUMN paid_amount DECIMAL(19, 2);
