-- The sheet's period numbering from: while set and the sheet's today is on or after it, a claim
-- opened is numbered within its period (<period name>-<n>), or X-<n> when in no period. Empty: off.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
-- A claim's number is now text: the sheet-wide number (1, 2, ...) or a number within a period.
-- A correction names the number of the claim it corrects in the same form.
DROP INDEX claim_sheet_number;
ALTER TABLE claim ALTER COLUMN claim_number VARCHAR(220);
ALTER TABLE claim ALTER COLUMN corrects VARCHAR(220);
-- A claim numbered within a period: the period's name (empty: in no period) and its place in it.
ALTER TABLE claim ADD COLUMN number_period VARCHAR(200);
ALTER TABLE claim ADD COLUMN number_seq INT;
