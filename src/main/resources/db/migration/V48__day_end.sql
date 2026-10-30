-- The sheet as it stood at the end of a day: written when the sheet's today moves on from that day,
-- holding (as JSON) every claim, person, rate and dated setting that its figures are made from, so a
-- statement can be taken as of a date gone by. The latest one written for a day on or before the date
-- is the sheet as it stood at the end of that date.
CREATE TABLE day_end (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    ended_on DATE         NOT NULL,
    state    CLOB         NOT NULL
);
CREATE INDEX day_end_sheet ON day_end (sheet_id, ended_on, id);
