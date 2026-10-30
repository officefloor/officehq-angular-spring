-- A sheet's holidays: a date, at most once per sheet. A claim's due-by, fixed when it is added, is moved
-- past the holidays on the sheet then.
CREATE TABLE holiday (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    holiday_date DATE     NOT NULL,
    CONSTRAINT holiday_unique UNIQUE (sheet_id, holiday_date)
);
