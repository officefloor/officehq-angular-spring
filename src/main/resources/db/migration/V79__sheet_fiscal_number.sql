-- Sheet-wide numbering restarts each fiscal year: a claim opened takes FY<yyyy>-<n>, counting
-- from 1 within the fiscal year of its due-by (by the sheet's fiscal start month), the sequence
-- shared across the sheet's tree. Each sheet keeps the last number it gave in each fiscal year;
-- numbers given before stay as they are.
CREATE TABLE sheet_fiscal_number (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    fiscal_year  INT          NOT NULL,
    last_number  INT          NOT NULL,
    CONSTRAINT sheet_fiscal_number_unique UNIQUE (sheet_id, fiscal_year)
);
