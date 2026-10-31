-- A sheet's history: after each change, what the sheet held (its dated settings, rates, people and claims, as JSON)
-- is kept under the sheet's today then; the latest kept under a day on or before a date is the sheet as it stood
-- at the end of that date, as a statement taken as of that date reads it.
CREATE TABLE sheet_snapshot (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    as_of    DATE         NOT NULL,
    state    CLOB         NOT NULL
);
CREATE INDEX sheet_snapshot_as_of ON sheet_snapshot (sheet_id, as_of, id);
