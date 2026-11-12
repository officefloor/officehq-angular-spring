-- A claim's proofs count, raised by one at a time. A claim whose due is above the sheet's proof threshold
-- (a dated setting, key proof-threshold; none: no threshold) in force on its date cannot be settled or
-- approved while it has none.
ALTER TABLE claim ADD COLUMN proofs INT NOT NULL DEFAULT 0;
