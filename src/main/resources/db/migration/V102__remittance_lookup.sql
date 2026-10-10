-- A remittance note lists the invoices a lump payment covered: the payments split from it, found by the lump.
CREATE INDEX payment_client_payment_idx ON payment (client_payment_id);
