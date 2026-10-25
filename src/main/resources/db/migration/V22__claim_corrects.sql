-- A claim with a negative amount is a correction: it names, by number, the settled claim of the
-- same person that it corrects. Other claims correct none. The amount's check (0.00 or more) has no
-- name to drop it by, so the amount moves to a new column without it.
ALTER TABLE claim ADD COLUMN corrects INTEGER;
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(12, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_correction_names_claim
    CHECK (amount >= 0 AND corrects IS NULL OR amount < 0 AND corrects IS NOT NULL);
