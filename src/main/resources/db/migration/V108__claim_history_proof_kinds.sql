-- A claim's saved states keep its proofs per kind, so the sheet read as of a past day shows them as
-- they stood then. A state kept before kinds were kept counts all its proofs as receipts.
ALTER TABLE claim_history ADD COLUMN proofs_receipt INT;
ALTER TABLE claim_history ADD COLUMN proofs_photo INT;
UPDATE claim_history SET proofs_receipt = proofs, proofs_photo = 0 WHERE proofs IS NOT NULL;
