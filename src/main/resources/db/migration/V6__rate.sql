-- The rate for each grade on a sheet: a percent with two decimals. A grade with no row has no rate.
CREATE TABLE rate (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)    NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    pct      DECIMAL(7, 2) NOT NULL CHECK (pct >= 0),
    CONSTRAINT rate_sheet_grade_unique UNIQUE (sheet_id, grade)
);
