-- The sheet's periods: a name, unique on the sheet without regard to case (name_key is the trimmed,
-- lower-cased name), and a from and to date, both inclusive, from on or before to. No two of a
-- sheet's periods overlap. A claim belongs to the period whose dates contain its date, or to none.
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(200) NOT NULL,
    name_key  VARCHAR(200) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CHECK (from_date <= to_date)
);
CREATE UNIQUE INDEX period_sheet_name ON period (sheet_id, name_key);
CREATE INDEX period_sheet_from ON period (sheet_id, from_date);
