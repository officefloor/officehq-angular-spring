-- A claim's number: when a claim is opened it takes the sheet's next number (1, 2, ...), with no
-- gaps and never reused. A DRAFT claim has none, and voiding a claim clears it. The sheet keeps the
-- last number it gave out; opening a claim takes the sheet row's lock to bump it.
ALTER TABLE sheet ADD COLUMN last_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number INT;
ALTER TABLE claim ADD CONSTRAINT claim_number_unique UNIQUE (sheet_id, claim_number);
