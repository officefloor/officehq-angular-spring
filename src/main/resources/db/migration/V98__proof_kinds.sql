-- A proof has a kind, receipt or photo (receipt when none is chosen). A claim keeps how many of its proofs
-- are photos; the rest of its proofs count are receipts.
ALTER TABLE claim ADD COLUMN proof_photos INT NOT NULL DEFAULT 0 CHECK (proof_photos >= 0);
-- The sheet's proof kinds: a comma separated list of kinds a claim above the proof threshold needs at least
-- one proof of each of before it can be settled or approved. None means none. Not dated.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(200);
