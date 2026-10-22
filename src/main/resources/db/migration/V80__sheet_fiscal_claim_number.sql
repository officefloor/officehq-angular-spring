-- The last sheet-wide claim number the sheet gave out in each fiscal year, named by the calendar year
-- the fiscal year starts in. Numbers given before numbering restarted each fiscal year stay as they are.
CREATE TABLE sheet_fiscal_claim_number (
    sheet_id    VARCHAR(64) NOT NULL REFERENCES sheet (id),
    fiscal_year INT NOT NULL,
    last_number INT NOT NULL
);
CREATE UNIQUE INDEX sheet_fiscal_claim_number_year ON sheet_fiscal_claim_number (sheet_id, fiscal_year);
