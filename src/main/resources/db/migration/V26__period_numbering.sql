-- The sheet's period numbering from: while set and the sheet's today is on or after it, a claim opened
-- is numbered within the period its date falls in (<period name>-<n>), or X-<n> when in no period;
-- null (blank) means off, and claims take the sheet-wide number.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
-- A claim numbered within a period: the sequence it counts in ('P<period id>', or 'X' for no period),
-- its place in that sequence (1, 2, ...) and the number as shown; all null for a sheet-wide number.
ALTER TABLE claim ADD COLUMN period_sequence VARCHAR(30);
ALTER TABLE claim ADD COLUMN period_seq_number INT;
ALTER TABLE claim ADD COLUMN period_number VARCHAR(120);
ALTER TABLE claim ADD CONSTRAINT claim_period_number_unique UNIQUE (sheet_id, period_sequence, period_seq_number);
-- A correction names the number of the claim it corrects as shown, a sheet-wide or a period number.
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(120);
