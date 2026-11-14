-- When a claim is opened it takes the sheet's next number (1, 2, ...): no gaps, never reused.
-- A draft has no number; voiding a claim clears it. The sheet keeps the last number it gave.
ALTER TABLE sheet ADD COLUMN last_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number INT;
