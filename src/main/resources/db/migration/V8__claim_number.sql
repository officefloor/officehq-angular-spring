-- A claim's number: given when the claim is opened, the next on its sheet (1, 2, ...) with no
-- gaps and never reused. The sheet keeps the last number it gave; a voided claim loses its number.
ALTER TABLE sheet ADD COLUMN last_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number INT;
ALTER TABLE claim ADD CONSTRAINT claim_number_unique UNIQUE (sheet_id, claim_number);
