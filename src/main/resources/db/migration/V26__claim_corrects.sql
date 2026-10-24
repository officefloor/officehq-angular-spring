-- A claim with a negative amount is a correction: it carries in corrects the number of the SETTLED
-- claim of the same person it corrects; corrects is null on any other claim.
ALTER TABLE claim ADD COLUMN corrects INTEGER;

-- The amount was checked to be 0.00 or more by an unnamed constraint: replace the column (which drops
-- that constraint) so only a correction can be negative.
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(12, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_amount_correction CHECK (amount >= 0 OR corrects IS NOT NULL);
