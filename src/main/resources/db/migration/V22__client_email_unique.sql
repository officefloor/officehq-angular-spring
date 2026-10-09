-- No two clients may share an email. Addresses are compared ignoring case (Ops@Acme.example and
-- ops@acme.example reach the same mailbox), so uniqueness is enforced on a lower-cased copy; this is
-- the backstop for the API check. Archived clients still hold their address.
ALTER TABLE client ADD COLUMN email_key VARCHAR(255) GENERATED ALWAYS AS (LOWER(email));
ALTER TABLE client ADD CONSTRAINT client_email_uq UNIQUE (email_key);
