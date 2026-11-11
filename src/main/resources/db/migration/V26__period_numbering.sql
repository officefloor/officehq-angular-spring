-- The sheet's period numbering from: while it is set and the sheet's today is on or after it, a claim opened is
-- numbered within its period (<period>-<n>), or X-<n> in no period. Null (blank) means off.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
-- A claim's number, and the number a correction names, are now text: a sheet-wide 1, 2, ... or P1-1, X-1, ...
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(255);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(255);
-- The last number given out in each period's sequence on a sheet (keyed by period name, or X for no period).
CREATE TABLE claim_number_sequence (
    sheet_id VARCHAR(100) NOT NULL,
    sequence_key VARCHAR(255) NOT NULL,
    last_number BIGINT NOT NULL,
    PRIMARY KEY (sheet_id, sequence_key)
);
