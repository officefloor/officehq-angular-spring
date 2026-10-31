-- The sheet's period numbering from: while it is set and the sheet's today is on or after it, a claim opened
-- is numbered within the period its date is in (<period name>-<n>), or X-<n> when in none. None means off.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;

-- A claim's number (and the number a correction names) is now text: a sheet-wide number (1, 2, ...) or a
-- number within a period (P1-1, X-1).
ALTER TABLE claim DROP CONSTRAINT claim_number_unique;
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(120);
ALTER TABLE claim ADD CONSTRAINT claim_number_unique UNIQUE (sheet_id, claim_number);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(120);

-- The last number given in each period sequence of a sheet, keyed by the number's prefix (the period's name,
-- or X for claims in no period). Numbers are never given again.
CREATE TABLE claim_sequence (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    prefix      VARCHAR(100) NOT NULL,
    last_number INT          NOT NULL,
    CONSTRAINT claim_sequence_sheet_prefix UNIQUE (sheet_id, prefix)
);
