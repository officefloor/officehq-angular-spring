-- How many proofs a claim has: a claim whose due is above the sheet's proof threshold on its date cannot be settled or
-- approved while it has none.
ALTER TABLE claim ADD COLUMN proofs INT NOT NULL DEFAULT 0;
