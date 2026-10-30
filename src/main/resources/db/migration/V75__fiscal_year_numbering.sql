-- Sheet-wide numbering restarts each fiscal year: a claim opened from now on is numbered <FY>-<n>, counting
-- from 1 within the fiscal year of its due-by, the sequence shared across the sheet's tree. A claim keeps the
-- fiscal year it was numbered in (null for a number given before, shown as it was).
ALTER TABLE claim ADD COLUMN fiscal_year VARCHAR(30);
ALTER TABLE claim DROP CONSTRAINT claim_number_unique;
ALTER TABLE claim ADD CONSTRAINT claim_number_unique UNIQUE (sheet_id, fiscal_year, claim_number);
-- The last number each sheet gave within a fiscal year; the next within the year is the one after the last
-- given on any sheet of the tree.
CREATE TABLE sheet_fiscal_number (
    sheet_id VARCHAR(100) NOT NULL,
    fiscal_year VARCHAR(30) NOT NULL,
    last_number INT NOT NULL,
    PRIMARY KEY (sheet_id, fiscal_year)
);
