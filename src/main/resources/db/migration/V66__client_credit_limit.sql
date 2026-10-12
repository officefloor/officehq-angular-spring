-- The most the client may owe, in the client's currency: an invoice that would take its balance over
-- it is not sent. NULL for no limit.
ALTER TABLE client ADD COLUMN credit_limit DECIMAL(19, 2);
