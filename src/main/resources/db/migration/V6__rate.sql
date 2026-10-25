-- The sheet's rate for each grade: a percent of 0.00 or more with two decimals. A grade with no
-- row has no rate. A claim's due is its amount times its person's grade's rate, divided by 100.
CREATE TABLE rate (
    sheet_id VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade    VARCHAR(1)    NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    pct      DECIMAL(5, 2) NOT NULL CHECK (pct >= 0),
    PRIMARY KEY (sheet_id, grade)
);
