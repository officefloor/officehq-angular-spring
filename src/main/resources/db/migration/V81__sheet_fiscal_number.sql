-- Sheet-wide numbering restarts each fiscal year: a claim opened is numbered FY<year>-<n>, the next in
-- the fiscal year its due-by falls in (by the sheet's fiscal start month), shared across the sheet's
-- tree. Each sheet keeps the last number it gave in each fiscal year; numbers already given stay.
CREATE TABLE sheet_fiscal_number (
    sheet_id    VARCHAR(100) NOT NULL,
    fiscal_year INT          NOT NULL,
    last_number BIGINT       NOT NULL,
    PRIMARY KEY (sheet_id, fiscal_year)
);
