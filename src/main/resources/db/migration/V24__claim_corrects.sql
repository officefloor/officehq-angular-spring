-- A correction: a claim with a negative amount, naming by number a SETTLED claim of the same person
-- that it corrects. Null on any other claim. The amount may now be below 0.00, but only on a
-- correction.
ALTER TABLE claim ADD COLUMN corrects INT;
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(14, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_correction CHECK (amount >= 0 OR corrects IS NOT NULL);
