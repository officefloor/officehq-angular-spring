-- The last sheet-wide claim number a sheet gave in each fiscal year: a claim opened takes
-- FY<year>-<n>, counting from 1 within the fiscal year of its due-by date, shared across the tree.
CREATE TABLE sheet_fiscal_claim_number (
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    fiscal_year INTEGER NOT NULL,
    last_claim_number BIGINT NOT NULL,
    PRIMARY KEY (sheet_id, fiscal_year)
);
