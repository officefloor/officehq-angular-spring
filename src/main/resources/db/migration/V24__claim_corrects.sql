-- A claim with a negative amount is a correction: it names, by number, a SETTLED claim of the
-- same person that it corrects. Null for any other claim.
ALTER TABLE claim ADD COLUMN corrects BIGINT;

-- The amount may now be negative, but only on a correction. Its old CHECK (amount >= 0) has a
-- generated name, so the column is replaced (dropping it drops that check) and copied over.
ALTER TABLE claim ADD COLUMN amount_signed DECIMAL(12, 2);
UPDATE claim SET amount_signed = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN amount_signed RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_amount_correction CHECK (amount >= 0 OR corrects IS NOT NULL);
