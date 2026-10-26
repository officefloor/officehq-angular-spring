-- The periods of a sheet: a name unique on the sheet and a from and to date, inclusive. No two periods of a sheet
-- overlap; a claim belongs to the period whose dates contain its date, or to none.
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(100) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL
);
CREATE UNIQUE INDEX period_sheet_name ON period (sheet_id, name);
CREATE INDEX period_sheet_from ON period (sheet_id, from_date);
