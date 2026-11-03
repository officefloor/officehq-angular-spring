-- Numbering per period: from the sheet's period-numbering-from date (none: off), a claim opened is
-- numbered within the period its date falls in (<period name>-<n>), or, in no period, in the
-- sheet's own X sequence (X-<n>). A claim's number, and the number a correction names, become text.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
ALTER TABLE sheet ADD COLUMN last_unperiod_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE period ADD COLUMN last_claim_number INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(120);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(120);
