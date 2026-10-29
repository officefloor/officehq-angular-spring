-- How many of a claim's proofs are photos; the rest of its proofs are receipts (a proof added before kinds were kept,
-- or added with none chosen, is a receipt).
ALTER TABLE claim ADD COLUMN proof_photos INT DEFAULT 0 NOT NULL CHECK (proof_photos >= 0);
-- The sheet's proof kinds: the kinds (receipt, photo) a claim above the proof threshold needs at least one proof of
-- each before it can be settled or approved, comma separated; null (blank) for none. Not dated.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(100);
