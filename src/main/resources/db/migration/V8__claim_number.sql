-- A claim's number: given when it is opened, the next on its sheet (1, 2, ...), never reused.
-- A DRAFT has none and voiding a claim clears it. The sheet keeps the last number it gave out.
ALTER TABLE sheet ADD COLUMN last_claim_number BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number BIGINT;
