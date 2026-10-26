-- The sheet's period numbering from: while it is set and the sheet's today is on or after it, a claim opened is
-- numbered within its period (<period name>-<n>), or X-<n> when it is in no period. Null (blank) means off.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
-- A claim's number is now the text it shows: a sheet-wide number (1, 2, ...) or a period number (P1-1, X-1).
-- A correction names the claim it corrects by that text.
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(140);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(140);
-- The sequence a period-numbered claim was counted in (the period's name, or X for no period) and its place in it;
-- null for a claim with a sheet-wide number or none.
ALTER TABLE claim ADD COLUMN number_sequence VARCHAR(120);
ALTER TABLE claim ADD COLUMN number_in_sequence BIGINT;
