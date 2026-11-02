-- A proof has a kind, receipt or photo; a claim keeps a count per kind beside its total. Existing proofs
-- were all added with no kind chosen, so they are receipts.
ALTER TABLE claim ADD COLUMN proofs_receipt INT NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN proofs_photo INT NOT NULL DEFAULT 0;
UPDATE claim SET proofs_receipt = proofs;
-- The sheet's proof kinds (not dated), comma separated: while set, a claim above the proof threshold
-- needs at least one proof of each listed kind to be settled or approved. Null means none.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(100);
