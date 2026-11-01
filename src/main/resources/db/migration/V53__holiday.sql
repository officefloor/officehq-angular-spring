-- The sheet's holidays, each date at most once; a claim's due-by is moved past them when it is added.
CREATE TABLE holiday (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    holiday_date DATE         NOT NULL
);
CREATE UNIQUE INDEX holiday_unique ON holiday (sheet_id, holiday_date);
