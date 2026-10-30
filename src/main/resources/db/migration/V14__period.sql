-- A sheet's periods: a name, unique on the sheet without regard to case, and a from and to date,
-- both inclusive. No two periods on a sheet overlap (checked when one is added).
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(100) NOT NULL,
    name_key  VARCHAR(100) GENERATED ALWAYS AS (LOWER(name)),
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CONSTRAINT period_dates CHECK (from_date <= to_date),
    CONSTRAINT period_name_unique UNIQUE (sheet_id, name_key)
);
CREATE INDEX period_sheet ON period (sheet_id, from_date);
