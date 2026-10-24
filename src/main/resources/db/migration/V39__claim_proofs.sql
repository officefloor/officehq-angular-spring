-- How many proofs a claim has been given; raised by one at a time while it is neither SETTLED nor VOID.
-- The sheet's proof threshold is a dated setting (proof-threshold) kept in sheet_setting.
ALTER TABLE claim ADD COLUMN proofs INT NOT NULL DEFAULT 0;
