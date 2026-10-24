-- The sheet's period numbering from: while it is set and the sheet's today is on or after it, a claim
-- opened is numbered within the period its date falls in (<period name>-<n>), or X-<n> when it falls in
-- none. Null means off. Claim numbers (and the numbers corrections name) are therefore text.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(120);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(120);

-- The last number given in each per-period sequence of a sheet: seq_key is period:<period id>, or none
-- for the claims dated in no period.
CREATE TABLE claim_sequence (
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    seq_key     VARCHAR(40)  NOT NULL,
    last_number INT          NOT NULL,
    PRIMARY KEY (sheet_id, seq_key)
);
