-- Each sheet's rate for a grade: a percent, 0.00 or more with two decimals. A grade with no row has
-- no rate.
CREATE TABLE rate (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)    NOT NULL,
    pct      DECIMAL(7, 2) NOT NULL CHECK (pct >= 0)
);
CREATE UNIQUE INDEX rate_sheet_grade ON rate (sheet_id, grade);
