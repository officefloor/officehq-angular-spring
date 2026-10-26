-- Every state a sheet has stood in: after each change to it, every row kept for the sheet (in every table that holds
-- its data) is stored as it then stood, with the sheet's today then (made_on). A sheet shown as of a date is the last
-- state stored on or before that date. The digest tells two states apart, so an unchanged state is not stored twice.
CREATE TABLE sheet_snapshot (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    made_on  DATE         NOT NULL,
    digest   VARCHAR(64)  NOT NULL,
    data     BLOB         NOT NULL
);
CREATE INDEX sheet_snapshot_sheet ON sheet_snapshot (sheet_id, id);
