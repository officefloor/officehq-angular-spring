-- A claim's proofs count, raised by one at a time while the claim is neither SETTLED nor VOID.
ALTER TABLE claim ADD COLUMN proofs INT NOT NULL DEFAULT 0;
-- The sheet's starting proof threshold (before every from-date, see sheet_setting): a claim whose
-- due is above the threshold in force on its date cannot be settled or approved with no proofs.
-- Null means none.
ALTER TABLE sheet ADD COLUMN proof_threshold DECIMAL(14, 2);
