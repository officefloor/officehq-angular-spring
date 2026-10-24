-- The statement that bound a claim to stated_due; null while the claim is unbound. Cancelling a
-- person's last statement unbinds the claims it bound.
ALTER TABLE claim ADD COLUMN stated_by BIGINT;
