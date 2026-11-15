-- A holiday on the sheet: a claim's due-by, as it is fixed at creation, falling on one is moved
-- to the next day that is not one. A date is on a sheet's list at most once.
CREATE TABLE sheet_holiday (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    holiday_date DATE       NOT NULL,
    UNIQUE (sheet_id, holiday_date)
);
