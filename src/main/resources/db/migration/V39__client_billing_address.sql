-- A client may have a billing address to send their invoices to; existing clients have none.
ALTER TABLE client ADD COLUMN billing_address VARCHAR(500);
