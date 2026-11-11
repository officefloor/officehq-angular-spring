-- The holidays of a sheet, each date at most once. A claim's due-by, fixed when it is added, that falls on a holiday
-- is moved to the next day that is not one; the holidays on the sheet then decide.
CREATE TABLE holiday (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    holiday_date DATE         NOT NULL
);
CREATE UNIQUE INDEX holiday_sheet_date ON holiday (sheet_id, holiday_date);
