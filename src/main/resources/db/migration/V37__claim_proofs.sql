-- A claim's proofs count, raised by one at a time. A claim whose due is above the proof threshold
-- in force on its date (a dated setting, key proof-threshold) cannot be settled or approved
-- while it has no proofs.
ALTER TABLE claim ADD COLUMN proofs INT NOT NULL DEFAULT 0;
