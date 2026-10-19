-- The periods of a sheet: a name, unique on the sheet without regard to case, and a from and to date,
-- both inclusive. No two periods of a sheet overlap; a claim belongs to the one whose dates contain its date.
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(200) NOT NULL,
    name_key  VARCHAR(200) GENERATED ALWAYS AS (LOWER(name)),
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CHECK (from_date <= to_date)
);
CREATE UNIQUE INDEX period_sheet_name ON period (sheet_id, name_key);
CREATE INDEX period_sheet_from ON period (sheet_id, from_date);
