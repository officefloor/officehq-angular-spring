-- The kind of each proof given to a claim, receipt or photo; proofs given before kinds were kept
-- have none and count as receipts.
ALTER TABLE claim_proof ADD COLUMN kind VARCHAR(20);
-- The sheet's proof kinds: a comma separated list of kinds a claim above the proof threshold needs
-- at least one proof of each before it is settled or approved; null (blank) is none. Not dated.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(100);
