-- A proof's kind, receipt or photo: a claim counts its proofs per kind. A proof added before kinds
-- were kept, or with none chosen, is a receipt.
ALTER TABLE claim ADD COLUMN proofs_receipt INT NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN proofs_photo INT NOT NULL DEFAULT 0;
UPDATE claim SET proofs_receipt = proofs;
-- A sheet's proof kinds, comma separated, not dated: a claim above the proof threshold needs at least
-- one proof of each before it can be settled or approved. Null (blank) asks for none.
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(200);
