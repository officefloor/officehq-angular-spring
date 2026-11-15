-- A proof has a kind, receipt or photo (receipt when none is chosen): of a claim's proofs, how many
-- are photos (the rest are receipts, as every proof added before kinds was).
ALTER TABLE claim ADD COLUMN photo_proofs INT NOT NULL DEFAULT 0;
-- The sheet's proof kinds: a comma separated list of kinds; while set, a claim above the proof
-- threshold needs at least one proof of each listed kind to be settled or approved. Null when
-- blank (none). Not dated.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(100);
