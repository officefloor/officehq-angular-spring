-- What a sheet held after each change to it, oldest first, so it can be shown as of a date: every
-- row the sheet owns, in every table, with the sheet's today once the change was made (empty: the
-- sheet as it was started, which stands for any date before its first change). A change that left
-- the sheet as it was records nothing.
CREATE TABLE sheet_snapshot (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL,
    taken_on DATE,
    data     VARBINARY NOT NULL
);
CREATE INDEX sheet_snapshot_sheet ON sheet_snapshot (sheet_id, id);
