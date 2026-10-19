-- The rate for each grade on a sheet: a percent with two decimals. A grade with no row has no rate.
CREATE TABLE rate (
    sheet_id VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)    NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    rate     DECIMAL(5, 2) NOT NULL CHECK (rate >= 0),
    PRIMARY KEY (sheet_id, grade)
);
