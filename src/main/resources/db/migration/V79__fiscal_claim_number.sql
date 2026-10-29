-- The last claim number a sheet gave in a fiscal year (FY<year>); a sheet's tree counts one sequence per fiscal year.
CREATE TABLE sheet_fiscal_claim_number (
    sheet_id VARCHAR(100) NOT NULL,
    fiscal_year INTEGER NOT NULL,
    last_claim_number BIGINT NOT NULL,
    PRIMARY KEY (sheet_id, fiscal_year)
);
