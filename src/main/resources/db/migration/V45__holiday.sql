-- The sheet's holidays, each date at most once. A claim's due-by falling on one, when it is fixed,
-- moves to the next day that is not a holiday; due-by dates already fixed are kept as they are.
CREATE TABLE holiday (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    holiday_date DATE         NOT NULL
);
CREATE UNIQUE INDEX holiday_once ON holiday (sheet_id, holiday_date);
