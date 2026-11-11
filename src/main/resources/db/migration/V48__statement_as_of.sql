-- What a statement as of a date needs to see the sheet as it stood then: the sheet's today when each change was
-- made. Null on rows made before this was kept: they count as made at any date.

-- The sheet's today when the statement was taken (taken_on is the date it was taken as of).
ALTER TABLE statement ADD COLUMN made_on DATE;
-- The sheet's today when the person was added, and when their retirement was last recorded.
ALTER TABLE person ADD COLUMN added_on DATE;
ALTER TABLE person ADD COLUMN retired_on DATE;
-- The sheet's today when the hold was added.
ALTER TABLE person_hold ADD COLUMN made_on DATE;

-- Each setting of a rate, in the order it was made, with the sheet's today then. A grade's rates as they stood at the
-- end of a date are the last settings of each from-date made on or before it.
CREATE TABLE rate_log (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade     VARCHAR(1)    NOT NULL,
    from_date DATE,
    to_date   DATE,
    pct       DECIMAL(5, 2) NOT NULL,
    set_on    DATE          NOT NULL
);
CREATE INDEX rate_log_sheet_grade ON rate_log (sheet_id, grade, id);
