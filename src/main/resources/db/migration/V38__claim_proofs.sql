-- A claim's proofs count, raised one at a time. A claim whose due is above the sheet's proof threshold
-- in force on its date cannot be settled or approved while it has none. Existing claims have none.
ALTER TABLE claim ADD COLUMN proofs INT NOT NULL DEFAULT 0;
