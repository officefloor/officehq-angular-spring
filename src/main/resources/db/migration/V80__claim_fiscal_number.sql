-- The last sheet-wide claim number given on a sheet in each fiscal year (FYyyyy): the sheets of a tree share one
-- sequence per fiscal year, numbered <FY>-<n>. Numbers already given stay as they were.
CREATE TABLE claim_fiscal_number (
    sheet_id VARCHAR(100) NOT NULL,
    fiscal_year VARCHAR(10) NOT NULL,
    last_number BIGINT NOT NULL,
    PRIMARY KEY (sheet_id, fiscal_year)
);
