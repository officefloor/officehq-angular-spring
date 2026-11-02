-- Period numbering from: while it is a date and the sheet's today is on or after it, a claim opened
-- is numbered within its period (<period name>-<n>, from 1 in each period), and one in no period
-- X-<n> in a sequence of its own; null (blank) is off. Claims opened before keep their sheet-wide
-- numbers, and the sheet-wide sequence carries on when it is cleared.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
ALTER TABLE sheet ADD COLUMN last_unperiod_number INT NOT NULL DEFAULT 0;
ALTER TABLE period ADD COLUMN last_claim_number INT NOT NULL DEFAULT 0;
-- A claim's number, and the number a correction names, are now text (1, 2, ... or P1-1, X-1, ...).
ALTER TABLE claim DROP CONSTRAINT claim_sheet_number_unique;
ALTER TABLE claim ALTER COLUMN number SET DATA TYPE VARCHAR(120);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(120);
ALTER TABLE claim ADD CONSTRAINT claim_sheet_number_unique UNIQUE (sheet_id, number);
