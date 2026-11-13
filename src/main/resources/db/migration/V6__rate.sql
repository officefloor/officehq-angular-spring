-- A sheet's rate for a grade: a percent with two decimals (0.00 or more). A grade has at most one
-- rate on a sheet; a grade with no rate has none, and its claims' due is n/a.
CREATE TABLE rate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    grade CHAR(1) NOT NULL,
    rate DECIMAL(7, 2) NOT NULL CHECK (rate >= 0)
);
CREATE UNIQUE INDEX rate_sheet_grade ON rate (sheet_id, grade);
