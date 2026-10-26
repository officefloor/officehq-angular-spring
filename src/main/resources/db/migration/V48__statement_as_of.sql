-- A statement may be taken as of a date on or before the sheet's today (null: taken now). Every change it reads back
-- carries the sheet's today it was made on (made_on), so a statement as of a date counts only the changes made while
-- the sheet's today was on or before it. A null made_on is a change made before this was kept: it always counts.
ALTER TABLE statement ADD COLUMN as_of DATE;

-- The sheet's today when the person was added, and when their retirement date was set.
ALTER TABLE person ADD COLUMN added_on DATE;
ALTER TABLE person ADD COLUMN retired_on DATE;

ALTER TABLE person_grade ADD COLUMN made_on DATE;
ALTER TABLE person_hold ADD COLUMN made_on DATE;

-- The sheet's today when the claim was deferred.
ALTER TABLE claim ADD COLUMN deferred_on DATE;

-- Every rate set on a sheet, in the order set (by id): the grade, its from- and to-date, the percent, and the sheet's
-- today then. The rate a grade had for a from-date as of a date is the last one set for it on or before that date.
CREATE TABLE rate_change (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade     VARCHAR(1)    NOT NULL,
    from_date DATE,
    to_date   DATE,
    pct       DECIMAL(7, 2) NOT NULL,
    made_on   DATE
);
CREATE INDEX rate_change_sheet ON rate_change (sheet_id, id);
INSERT INTO rate_change (sheet_id, grade, from_date, to_date, pct, made_on)
    SELECT sheet_id, grade, from_date, to_date, pct, NULL FROM rate ORDER BY id;

-- Every value recorded for a dated setting of a sheet, in the order recorded (pos), with the sheet's today then.
CREATE TABLE sheet_setting_change (
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    pos           INT          NOT NULL,
    setting_key   VARCHAR(20)  NOT NULL,
    from_date     DATE         NOT NULL,
    setting_value VARCHAR(30),
    made_on       DATE,
    PRIMARY KEY (sheet_id, pos)
);
INSERT INTO sheet_setting_change (sheet_id, pos, setting_key, from_date, setting_value, made_on)
    SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY setting_key, from_date) - 1, setting_key,
           from_date, setting_value, NULL FROM sheet_setting;
