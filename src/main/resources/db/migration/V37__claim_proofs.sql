-- A claim counts the proofs given for it, raised one at a time while it is neither SETTLED nor VOID. A claim whose
-- due is above the sheet's proof threshold in force on its date (dated setting key proof-threshold; none means no
-- threshold) cannot be settled or approved while it has no proofs.
ALTER TABLE claim ADD COLUMN proofs INTEGER NOT NULL DEFAULT 0;
