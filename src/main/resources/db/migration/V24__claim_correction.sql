-- A claim with a negative amount is a correction: it names, by number, a settled claim of the same
-- person that it corrects. The amount may now be below 0.00 (the column is rebuilt without its
-- check); a claim that is not a correction corrects nothing (null).
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(14, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD COLUMN corrects INT;
