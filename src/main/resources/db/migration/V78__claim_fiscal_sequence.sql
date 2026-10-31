-- Sheet-wide numbering restarts each fiscal year: a claim opened outside per-period numbering is numbered
-- FY<year>-<n>, counting from 1 within the fiscal year (by the opening sheet's fiscal start month) of the date that
-- places it in a period, the sequence shared across the sheet's tree. Numbers already given stay. This keeps the
-- last number each sheet gave in each fiscal year; numbers are never given again.
CREATE TABLE claim_fiscal_sequence (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    fiscal_year INT          NOT NULL,
    last_number INT          NOT NULL,
    CONSTRAINT claim_fiscal_sequence_sheet_year UNIQUE (sheet_id, fiscal_year)
);
