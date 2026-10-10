-- A client can have a credit limit: the most the office lets them owe, in their currency. Existing
-- clients have none set.
ALTER TABLE client ADD COLUMN credit_limit DECIMAL(12, 2);
ALTER TABLE client ADD CONSTRAINT client_credit_limit_not_negative CHECK (credit_limit IS NULL OR credit_limit >= 0);
