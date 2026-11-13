-- A claim with a negative amount is a correction: it names, by number, a SETTLED claim of the same
-- person that it corrects. The amount may now be below 0.00 (its first check is dropped with the
-- column it was declared on), and only a correction names a claim it corrects.
ALTER TABLE claim ADD COLUMN amount_signed DECIMAL(12, 2);
UPDATE claim SET amount_signed = amount;
ALTER TABLE claim DROP COLUMN amount;
ALTER TABLE claim ALTER COLUMN amount_signed RENAME TO amount;
ALTER TABLE claim ALTER COLUMN amount SET NOT NULL;
ALTER TABLE claim ADD COLUMN corrects_number BIGINT;
ALTER TABLE claim ADD CONSTRAINT claim_correction_names CHECK ((amount < 0) = (corrects_number IS NOT NULL));
