-- A proof has a kind, receipt or photo: a claim counts its proofs of each kind (the proofs it had
-- before kinds were receipts). The sheet's proof kinds: a comma separated list of kinds a claim above
-- the proof threshold needs a proof of each before it is settled or approved; null for none. Not dated.
ALTER TABLE claim ADD COLUMN proofs_receipt INT NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN proofs_photo INT NOT NULL DEFAULT 0;
UPDATE claim SET proofs_receipt = proofs;
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(200);
