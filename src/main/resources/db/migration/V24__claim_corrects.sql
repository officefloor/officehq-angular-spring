-- A claim's amount may be negative: a correction, which names the number of a SETTLED claim of the
-- same person that it corrects. The amount column is remade without its old "0.00 or more" check.
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(14, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
-- The number of the claim a correction corrects; null for any other claim.
ALTER TABLE claim ADD COLUMN corrects INT;
