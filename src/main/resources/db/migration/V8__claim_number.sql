-- An opened claim takes the sheet's next claim number: 1, 2, ... with no gaps, never reused. The
-- sheet counts the numbers it has given out; a draft has no number and voiding a claim clears it.
ALTER TABLE sheet ADD COLUMN claim_numbers_used INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN claim_number INT;
