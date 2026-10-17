-- A claim with a negative amount is a correction: it names, by number, a SETTLED claim of the same
-- person that it corrects (null for any other claim). The amount may now be below 0, so the column
-- is rebuilt without its old 0-or-more check.
ALTER TABLE claim ADD COLUMN signed_amount DECIMAL(12, 2);
UPDATE claim SET signed_amount = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN signed_amount RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD COLUMN corrects BIGINT;
