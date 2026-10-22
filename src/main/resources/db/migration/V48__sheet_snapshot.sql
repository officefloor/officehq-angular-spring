-- The sheet as it stood at the end of a date: kept as the sheet's today moves on from that date, with
-- everything its figures are read from (settings, rates, people and claims) as JSON. A statement taken
-- as of a past date reads the last one kept for a date on or before it.
CREATE TABLE sheet_snapshot (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(64)    NOT NULL REFERENCES sheet (id),
    as_of     DATE           NOT NULL,
    state     CLOB           NOT NULL
);
CREATE INDEX sheet_snapshot_sheet ON sheet_snapshot (sheet_id, as_of);
