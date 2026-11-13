-- A sheet's holidays: a claim's due-by, fixed when it is added, is moved past any holiday on the
-- sheet then. A date is on a sheet's list at most once.
CREATE TABLE holiday (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    holiday_date DATE NOT NULL
);
CREATE UNIQUE INDEX holiday_sheet_date ON holiday (sheet_id, holiday_date);
