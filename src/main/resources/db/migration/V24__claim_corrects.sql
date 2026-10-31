-- A claim with a negative amount is a correction: it names, by number, the SETTLED claim of the same
-- person that it corrects. None on any other claim.
ALTER TABLE claim ADD COLUMN corrects INTEGER;

-- An amount may now be negative (a correction) only when it names the claim it corrects. The amount's
-- old unnamed check (0 or more) goes with the column it is on, so the column is replaced in place.
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(14, 2) BEFORE amount;
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_correction CHECK (amount >= 0 OR corrects IS NOT NULL);
