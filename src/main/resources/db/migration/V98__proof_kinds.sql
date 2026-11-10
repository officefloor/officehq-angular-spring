-- A proof has a kind, receipt or photo; a claim's photo proofs are counted, the rest of its proofs
-- are receipts (every proof added so far is a receipt).
ALTER TABLE claim ADD COLUMN photo_proofs INT NOT NULL DEFAULT 0;
-- The sheet's proof kinds (none: off), comma separated: while set, a claim above the proof threshold
-- needs at least one proof of each before it can be settled or approved. Not dated.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(100);
