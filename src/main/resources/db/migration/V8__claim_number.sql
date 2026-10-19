-- A claim gets the sheet's next number when it is opened: 1, 2, ... with no gaps and never reused.
-- A draft has no number, and voiding a claim clears it. The sheet keeps the last number it gave.
ALTER TABLE sheet ADD COLUMN last_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number INT;
