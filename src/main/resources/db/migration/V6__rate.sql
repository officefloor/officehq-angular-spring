-- The rate for each grade on a sheet: a percent with two decimals. A grade has at most one rate;
-- a grade with no row has no rate.
CREATE TABLE rate (
    id       BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade    CHAR(1)      NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    rate     DECIMAL(5, 2) NOT NULL CHECK (rate >= 0),
    CONSTRAINT rate_sheet_grade UNIQUE (sheet_id, grade)
);
