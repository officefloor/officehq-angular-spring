-- What each sheet held at the end of each of its todays, for the as-of view: after every change
-- the sheet's rows are kept against the today the change was made on (the latest change on that
-- today wins), numbered in the order the changes were made. Not referenced by, nor referencing, the
-- sheet's own tables, so it is never part of what it records.
CREATE SEQUENCE sheet_history_seq;
CREATE TABLE sheet_history (
    sheet_id  VARCHAR(100) NOT NULL,
    today     DATE         NOT NULL,
    seq       BIGINT       NOT NULL,
    rows_data BLOB         NOT NULL,
    PRIMARY KEY (sheet_id, today)
);
CREATE INDEX sheet_history_seq_order ON sheet_history (sheet_id, seq);
