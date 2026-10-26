-- The rate for each grade on a sheet: a percent with two decimals. A grade with no row has no rate.
CREATE TABLE rate (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)    NOT NULL,
    pct      DECIMAL(7, 2) NOT NULL CHECK (pct >= 0)
);
CREATE UNIQUE INDEX rate_sheet_grade ON rate (sheet_id, grade);
