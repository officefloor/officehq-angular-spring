-- A claim's number is now text: the sheet-wide 1, 2, ... as before, or, while the sheet's
-- period-numbering-from setting is in force, <period name>-<n> within the claim's period, or X-<n>
-- for a claim in no period. A correction names the claim it corrects by that text.
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(220);
ALTER TABLE claim ALTER COLUMN corrects SET DATA TYPE VARCHAR(220);
-- How many numbers each per-period sequence of a sheet has given out; 'period:<id>' counts a
-- period's, 'none' those of claims in no period. A sequence with no row has given none.
CREATE TABLE claim_number_sequence (
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    sequence_key VARCHAR(100) NOT NULL,
    used         INT          NOT NULL,
    PRIMARY KEY (sheet_id, sequence_key)
);
