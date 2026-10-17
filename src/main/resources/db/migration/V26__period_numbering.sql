-- The sheet's period numbering from: while it is set and the sheet's today is on or after it, a
-- claim opened is numbered within its period, <period name>-<n>, or X-<n> when it is in no period;
-- null for off (claims take the sheet-wide number).
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
-- A claim number is now text: the sheet-wide 1, 2, ... or a period number such as P1-1. A
-- correction names the number it corrects in the same form.
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(120);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(120);
-- The last number given in each period's sequence on a sheet, keyed by the number's prefix (the
-- period's name, or X for claims in no period), so a number is never given again.
CREATE TABLE claim_sequence (
    sheet_id    VARCHAR(100) NOT NULL,
    prefix      VARCHAR(100) NOT NULL,
    last_number BIGINT NOT NULL,
    CONSTRAINT claim_sequence_pk PRIMARY KEY (sheet_id, prefix)
);
