-- How many proofs a claim has; it starts at 0. A claim whose due is above the sheet's proof threshold
-- in force on its date cannot be settled or approved while it has none.
ALTER TABLE claim ADD COLUMN proofs INT DEFAULT 0 NOT NULL;
