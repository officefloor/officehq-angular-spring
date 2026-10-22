-- Period numbering: from the sheet's period-numbering-from date (null: off), a claim opened while the
-- sheet's today is on or after it is numbered within the period its date falls in, <period name>-<n>,
-- counting from 1 in each period (the period keeps the last it gave out), or X-<n> in the sheet's own
-- sequence for a claim in no period. Claims numbered before keep their sheet-wide numbers, and the
-- sheet-wide sequence carries on when the setting is cleared. A claim's number, and the number a
-- correction names, are now text.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
ALTER TABLE sheet ADD COLUMN last_unperiod_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE period ADD COLUMN last_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ALTER COLUMN claim_number VARCHAR(220);
ALTER TABLE claim ALTER COLUMN corrects VARCHAR(220);
