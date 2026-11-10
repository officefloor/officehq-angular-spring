-- The last claim number a sheet gave in each fiscal year (FY<yyyy>); a tree's sheets share one sequence per fiscal year.
CREATE TABLE fiscal_claim_number (
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    fiscal_year VARCHAR(16)  NOT NULL,
    last_number INTEGER      NOT NULL,
    PRIMARY KEY (sheet_id, fiscal_year)
);
