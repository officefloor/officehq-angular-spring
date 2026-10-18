-- The sheet's rate for a grade: a percent with two decimals (0.00 or more). A grade has at most one
-- rate on a sheet; setting it again replaces it. A grade with no rate has none.
CREATE TABLE rate (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)   NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    pct      DECIMAL(5, 2) NOT NULL CHECK (pct >= 0)
);
CREATE UNIQUE INDEX rate_sheet_grade ON rate (sheet_id, grade);
