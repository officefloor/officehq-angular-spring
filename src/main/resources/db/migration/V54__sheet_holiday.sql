-- The holidays on a sheet, one row each: a claim's due-by fixed when it is added is moved past them.
CREATE TABLE sheet_holiday (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    holiday_date DATE         NOT NULL,
    CONSTRAINT sheet_holiday_unique UNIQUE (sheet_id, holiday_date)
);
