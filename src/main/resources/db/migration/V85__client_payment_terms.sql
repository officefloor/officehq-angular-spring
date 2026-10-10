-- A client's payment terms: the number of days they have to pay an invoice (e.g. net 30); none when not agreed.
ALTER TABLE client ADD COLUMN payment_terms_days INTEGER;
ALTER TABLE client ADD CONSTRAINT client_payment_terms_days_range CHECK (payment_terms_days IS NULL OR payment_terms_days BETWEEN 0 AND 365);
