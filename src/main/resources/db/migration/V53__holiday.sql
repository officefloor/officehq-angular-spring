-- The holidays of a sheet, each day at most once. A claim's due-by fixed at its creation is moved
-- past the holidays on the sheet then.
CREATE TABLE holiday (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100) NOT NULL REFERENCES sheet (id),
    holiday_date DATE         NOT NULL,
    added_on     DATE         NOT NULL,
    CONSTRAINT holiday_unique UNIQUE (sheet_id, holiday_date)
);
