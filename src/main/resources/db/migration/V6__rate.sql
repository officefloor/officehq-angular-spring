-- The rate for a grade on a sheet: a percent with two decimals (0.00 or more). A sheet holds at
-- most one rate per grade; a grade with no row has no rate.
CREATE TABLE rate (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(64)   NOT NULL REFERENCES sheet (id),
    grade    CHAR(1)       NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    pct      DECIMAL(5, 2) NOT NULL CHECK (pct >= 0)
);
CREATE UNIQUE INDEX rate_sheet_grade ON rate (sheet_id, grade);
