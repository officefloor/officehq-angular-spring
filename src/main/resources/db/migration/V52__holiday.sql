-- A holiday on a sheet: a date, on the sheet's list at most once. A claim's due-by, fixed when it
-- is added, is moved past the holidays on the list then.
CREATE TABLE holiday (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(64) NOT NULL REFERENCES sheet (id),
    holiday_date DATE        NOT NULL
);
CREATE UNIQUE INDEX holiday_sheet_date ON holiday (sheet_id, holiday_date);
