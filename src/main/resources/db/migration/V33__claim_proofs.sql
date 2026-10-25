-- A claim's proofs count, raised by one at a time while it is not SETTLED or VOID. A claim whose due
-- is above the sheet's proof threshold cannot be settled or approved while it has none.
ALTER TABLE claim ADD COLUMN proofs INTEGER NOT NULL DEFAULT 0;
