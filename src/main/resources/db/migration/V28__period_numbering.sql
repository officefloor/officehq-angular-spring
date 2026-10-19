-- A sheet's period numbering from: while it is a date and the sheet's today is on or after it, a claim opened is
-- numbered within its period, <period name>-<n>, or X-<n> when in no period. None means off. A claim's number,
-- and the number a correction names, are therefore text; claims numbered before keep their sheet-wide numbers.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
ALTER TABLE claim ALTER COLUMN claim_number VARCHAR(255);
ALTER TABLE claim ALTER COLUMN corrects VARCHAR(255);
