-- A proof's kind: receipt or photo. A claim's proofs that are not photos are receipts.
ALTER TABLE claim ADD COLUMN photo_proofs INT NOT NULL DEFAULT 0;
-- The kinds of proof (comma separated) a claim above the proof threshold needs one of each of; null: none. Not dated.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(100);
