-- A claim gets the sheet's next number when it is opened: 1, 2, ... with no gaps and never reused.
-- The sheet keeps the last number given; voiding a claim clears its number.
ALTER TABLE sheet ADD COLUMN last_claim_number BIGINT NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN claim_number BIGINT;
CREATE UNIQUE INDEX claim_sheet_number ON claim (sheet_id, claim_number);
