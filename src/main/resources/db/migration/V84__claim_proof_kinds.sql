-- A proof has a kind, receipt or photo; the claim keeps a count per kind beside its total. A proof added
-- before kinds were kept was a receipt.
ALTER TABLE claim ADD COLUMN proofs_receipt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN proofs_photo INTEGER NOT NULL DEFAULT 0;
UPDATE claim SET proofs_receipt = proofs;
