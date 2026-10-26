-- A correction (a claim with a negative amount) names, by number, the settled claim of the same person it corrects.
-- Null for any other claim.
ALTER TABLE claim ADD COLUMN corrects BIGINT;

-- The amount may now be negative, but only on a correction. Its old check has no name to drop it by, so the
-- column is rebuilt.
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(14, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_amount_correction CHECK (amount >= 0 OR corrects IS NOT NULL);
