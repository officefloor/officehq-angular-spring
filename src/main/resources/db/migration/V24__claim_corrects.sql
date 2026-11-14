-- A correction is a claim with a negative amount; it names the number of the SETTLED claim of the
-- same person that it corrects (none for every other claim). The amount's unnamed CHECK (amount >= 0)
-- goes with its column, which is rebuilt to allow a negative amount, but only for a correction.
ALTER TABLE claim ADD COLUMN corrects INTEGER;
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(15, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_amount_correction CHECK (amount >= 0 OR corrects IS NOT NULL);
