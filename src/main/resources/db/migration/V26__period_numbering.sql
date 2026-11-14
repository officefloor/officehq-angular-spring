-- The sheet's period numbering from-date: while it is set and the sheet's today is on or after it,
-- a claim opened is numbered within its period (<period name>-<n>), or X-<n> when in no period;
-- null when blank (off). A claim's number, and the number a correction corrects, become text.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(120);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(120);
