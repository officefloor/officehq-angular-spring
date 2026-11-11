-- How each sheet stood at the end of each of its todays: after every change made to a sheet, every row the sheet
-- keeps is copied here under the sheet's today then (the last change of a today replaces its earlier copy). A sheet
-- shown as of a date is the copy with the latest today on or before it, the latest made first; none: an empty sheet.
CREATE SEQUENCE sheet_snapshot_seq;
CREATE TABLE sheet_snapshot (
    sheet_id VARCHAR(100) NOT NULL,
    made_on  DATE         NOT NULL,
    seq      BIGINT       NOT NULL,
    data     BLOB         NOT NULL,
    PRIMARY KEY (sheet_id, made_on)
);
