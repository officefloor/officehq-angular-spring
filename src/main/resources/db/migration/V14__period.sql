-- A period on a sheet: a name, unique on the sheet without regard to case, and the days it runs
-- from and to, both inclusive. No two periods of a sheet overlap.
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(100) NOT NULL,
    name_key  VARCHAR(100) GENERATED ALWAYS AS (LOWER(TRIM(name))),
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CONSTRAINT period_dates CHECK (from_date <= to_date),
    CONSTRAINT period_sheet_name_unique UNIQUE (sheet_id, name_key)
);
CREATE INDEX period_sheet ON period (sheet_id, from_date);
