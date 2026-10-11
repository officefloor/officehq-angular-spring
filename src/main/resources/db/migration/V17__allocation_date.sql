-- The day a part of a payment was applied to its invoice, when later than the payment itself (credit
-- held on the client's account used when an invoice is sent); null means the payment's own date.
ALTER TABLE payment_allocation ADD COLUMN applied_date DATE;
