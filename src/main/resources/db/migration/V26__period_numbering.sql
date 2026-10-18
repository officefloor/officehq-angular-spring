-- The sheet's period numbering from-date: while it is set and the sheet's today is on or after it, a
-- claim opened is numbered within the period its date falls in (<period name>-<n>, from 1 in each
-- period), and one in no period X-<n> in a sequence of its own. None (blank) means off: the sheet-wide
-- sequence (1, 2, ...) is used, carrying on from where it was. A claim's number, and the number a
-- correction names, are therefore text.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
DROP INDEX claim_sheet_number;
ALTER TABLE claim ALTER COLUMN number SET DATA TYPE VARCHAR(220);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(220);
CREATE UNIQUE INDEX claim_sheet_number ON claim (sheet_id, number);
-- The last number given out in each of a sheet's period sequences, by prefix (the period's name, or X
-- for no period); numbers are never reused.
CREATE TABLE claim_number_sequence (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    prefix      VARCHAR(200) NOT NULL,
    last_number INT          NOT NULL
);
CREATE UNIQUE INDEX claim_number_sequence_prefix ON claim_number_sequence (sheet_id, prefix);
