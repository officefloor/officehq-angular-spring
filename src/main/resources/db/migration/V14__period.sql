-- The sheet's periods: a name unique on the sheet (ignoring case and surrounding spaces) and a
-- from and to date, both inclusive. No two periods of a sheet overlap. A claim belongs to the
-- period whose dates contain its date, or to none.
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(100) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    name_key  VARCHAR(200) GENERATED ALWAYS AS (LOWER(name)),
    CHECK (from_date <= to_date)
);
CREATE UNIQUE INDEX period_sheet_name ON period (sheet_id, name_key);
