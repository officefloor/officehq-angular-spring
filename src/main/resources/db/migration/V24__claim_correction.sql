-- A correction: a claim with a negative amount, naming by number the SETTLED claim of the same
-- person that it corrects. The amount may now be below zero (only a correction's is), so the old
-- 0.00-or-more check goes with the column it was on: the amount moves to a column without it.
ALTER TABLE claim ADD COLUMN amount_signed DECIMAL(14, 2);
UPDATE claim SET amount_signed = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN amount_signed RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
-- The number of the claim a correction corrects; none for any other claim.
ALTER TABLE claim ADD COLUMN corrects INT;
