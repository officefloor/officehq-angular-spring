-- A claim with a negative amount is a correction: it names, by number, the settled claim of the same
-- person it corrects; other claims have none. The amount's unnamed check (amount >= 0) goes with
-- the column it is on, so the column is replaced, and a negative amount now needs what it corrects.
ALTER TABLE claim ADD COLUMN corrects INT;
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(15, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_correction CHECK (amount >= 0 OR corrects IS NOT NULL);
