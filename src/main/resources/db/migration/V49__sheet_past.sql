-- The whole sheet as it stood at the end of a day: written when the sheet's today moves on from that day,
-- holding (as JSON) every row of the sheet in every table, so the screen can show the sheet as of a date
-- gone by. The latest one written for a day on or before the date is the sheet as it stood then.
CREATE TABLE sheet_past (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    ended_on DATE         NOT NULL,
    state    CLOB         NOT NULL
);
CREATE INDEX sheet_past_sheet ON sheet_past (sheet_id, ended_on, id);
