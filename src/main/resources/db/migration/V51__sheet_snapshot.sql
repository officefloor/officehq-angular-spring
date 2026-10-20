-- A sheet may be shown as of a date: as it stood at the end of that date, every change made while the sheet's today
-- was on or before it counted and every later one left out. Each change therefore keeps the whole sheet as it stood
-- once made, with the sheet's today then (its made-on date). The sheet as it stood before its first change is kept
-- with no made-on date, and stands for every date before the first change.
CREATE TABLE sheet_snapshot (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    made_on  DATE,
    data     BLOB         NOT NULL
);
CREATE INDEX sheet_snapshot_sheet ON sheet_snapshot (sheet_id, id);
