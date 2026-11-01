-- A claim's number: given when the claim is opened, the next on its sheet (1, 2, ...), never
-- reused. A DRAFT claim has none; voiding a claim clears it. The sheet keeps the last number given.
ALTER TABLE sheet ADD COLUMN last_claim_number BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number BIGINT;
CREATE UNIQUE INDEX claim_sheet_number ON claim (sheet_id, claim_number);
