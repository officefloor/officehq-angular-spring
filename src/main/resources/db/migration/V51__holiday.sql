-- The holidays on a sheet: a claim's due-by fixed at its creation is moved past them. Each date is on a sheet's
-- list at most once. Ids rise in the order holidays were added.
CREATE TABLE holiday (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    holiday_date DATE         NOT NULL
);
CREATE UNIQUE INDEX holiday_sheet_date ON holiday (sheet_id, holiday_date);
