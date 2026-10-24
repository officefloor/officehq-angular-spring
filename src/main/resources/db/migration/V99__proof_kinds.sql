-- How many of a claim's proofs are photos; the rest are receipts (a proof added without a kind is a receipt).
ALTER TABLE claim ADD COLUMN proofs_photo INT NOT NULL DEFAULT 0;
-- The proof kinds a claim above the proof threshold needs at least one proof of each of before it is
-- settled or approved, comma separated; null when none are listed.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(100);
