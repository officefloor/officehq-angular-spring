-- A claim's number: given when it is opened, the next on its sheet (1, 2, ...), no gaps and never
-- reused. A draft has none; voiding a claim clears it. The sheet keeps the last number it gave.
ALTER TABLE sheet ADD COLUMN last_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number INT;
CREATE UNIQUE INDEX claim_sheet_number ON claim (sheet_id, claim_number);
