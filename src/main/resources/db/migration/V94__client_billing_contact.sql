-- A client can name a separate billing contact: who their bills should go to, when that is not the
-- client's own email. Existing clients have none recorded.
ALTER TABLE client ADD COLUMN billing_contact VARCHAR(255);
