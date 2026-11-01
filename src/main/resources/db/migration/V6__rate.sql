-- The sheet's rate for each grade: a percent with two decimals (0.00 or more). A grade with no row
-- has no rate, and its claims' due is n/a.
CREATE TABLE rate (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    CHAR(1)      NOT NULL,
    pct      DECIMAL(7, 2) NOT NULL CHECK (pct >= 0)
);
CREATE UNIQUE INDEX rate_sheet_grade ON rate (sheet_id, grade);
