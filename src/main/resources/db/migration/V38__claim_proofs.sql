-- How many proofs a claim has. A claim whose due is above the sheet's proof threshold in force on its
-- date cannot be settled or approved while it has none. The threshold is a dated setting
-- (setting_value key 'proof-threshold'; blank: none).
ALTER TABLE claim ADD COLUMN proofs INT NOT NULL DEFAULT 0;
