-- Every change to what a sheet's figures are made of (its people, claims, rates, dated settings and
-- the sheet itself), one line per row as it stood after the change, in the order they happened, with
-- the sheet's today when it was made; state is null when the row was removed. A sheet's state as of a
-- date is, for each row, its last line made on or before that date.
CREATE TABLE sheet_history (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL,
    made_on   DATE         NOT NULL,
    entity    VARCHAR(30)  NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    state     CLOB
);
CREATE INDEX sheet_history_sheet ON sheet_history (sheet_id, made_on, id);
