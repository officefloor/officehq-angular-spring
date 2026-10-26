-- How many proofs a claim has: raised by one at a time while it is DRAFT, OPEN or PENDING. A claim whose due is above
-- the proof threshold in force on its date cannot be settled or approved while it has none.
ALTER TABLE claim ADD COLUMN proofs INT DEFAULT 0 NOT NULL CHECK (proofs >= 0);
