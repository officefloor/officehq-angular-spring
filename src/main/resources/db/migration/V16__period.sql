-- A sheet's periods: a name, unique on the sheet without regard to case (name_key is the name as
-- compared), and a from and to date, both inclusive. No two periods of a sheet overlap.
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(100) NOT NULL,
    name_key  VARCHAR(100) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CHECK (from_date <= to_date)
);
CREATE UNIQUE INDEX period_sheet_name ON period (sheet_id, name_key);
