-- The sheet's holidays, each date at most once: a claim's due-by fixed at its creation is moved past them.
CREATE TABLE holiday (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    on_date  DATE         NOT NULL
);
CREATE UNIQUE INDEX holiday_once ON holiday (sheet_id, on_date);
