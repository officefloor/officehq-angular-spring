-- A claim's number: given when the claim is opened, the next on its sheet (1, 2, ...), never
-- reused; a draft has none and voiding clears it. The sheet keeps the last number it gave.
ALTER TABLE sheet ADD COLUMN last_claim_number BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number BIGINT;
ALTER TABLE claim ADD CONSTRAINT claim_number_unique UNIQUE (sheet_id, claim_number);
