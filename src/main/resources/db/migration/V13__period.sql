-- Periods on a sheet: a name unique on the sheet (regardless of case, which the service ensures)
-- and an inclusive from and to date. No two periods on a sheet overlap (the service ensures it).
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(100) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CONSTRAINT period_dates CHECK (from_date <= to_date),
    CONSTRAINT period_sheet_name UNIQUE (sheet_id, name)
);
