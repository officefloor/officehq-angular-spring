-- Periods on a sheet: a name unique on the sheet and an inclusive from and to date. No two periods
-- of a sheet overlap (checked when one is added, with the sheet locked); a claim belongs to the
-- period whose dates contain its date, or to none.
CREATE TABLE period (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(100) NOT NULL,
    from_date DATE         NOT NULL,
    to_date   DATE         NOT NULL,
    CONSTRAINT period_from_to CHECK (from_date <= to_date),
    CONSTRAINT period_sheet_name UNIQUE (sheet_id, name)
);
