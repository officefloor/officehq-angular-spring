-- A claim with a negative amount is a correction: it names, by number, a SETTLED claim of the same
-- person that it corrects. The amount's old check (0.00 or more) goes with its column; any other
-- claim names none.
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(15, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD COLUMN corrects_number INTEGER;
ALTER TABLE claim ADD CONSTRAINT claim_correction CHECK ((amount < 0) = (corrects_number IS NOT NULL));
