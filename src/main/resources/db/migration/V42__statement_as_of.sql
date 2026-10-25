-- A statement can be taken as of a date on or before the sheet's today: it binds the person's claims
-- as they stood at the end of that date, counting every change made while the sheet's today was on or
-- before it. So each change a figure depends on keeps the sheet's today it was made on (made_on; null:
-- made before this was kept, counting on every date).

-- The statement's date: the date it was taken as of, else the sheet's today when it was taken.
ALTER TABLE statement ADD COLUMN stated_on DATE;
UPDATE statement SET stated_on = taken_on;
ALTER TABLE statement ALTER COLUMN stated_on SET NOT NULL;

-- Each binding of a claim to a figure by a statement, and each loss of one (a later statement leaving it
-- out, its statement cancelled, its moving or splitting), oldest first.
CREATE TABLE claim_binding (
    claim_id   BIGINT  NOT NULL REFERENCES claim (id),
    position   INT     NOT NULL,
    on_date    DATE,
    stated     BOOLEAN NOT NULL,
    stated_due DECIMAL(12, 2),
    PRIMARY KEY (claim_id, position)
);
INSERT INTO claim_binding (claim_id, position, stated, stated_due)
    SELECT id, 0, TRUE, stated_due FROM claim WHERE stated;

-- Each setting of a rate, oldest first: a rate set for a grade and from-date replaces the one before it.
CREATE TABLE rate_change (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade     VARCHAR(1)    NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    from_date DATE,
    to_date   DATE,
    pct       DECIMAL(5, 2) NOT NULL,
    made_on   DATE
);
CREATE INDEX rate_change_sheet ON rate_change (sheet_id, id);
INSERT INTO rate_change (sheet_id, grade, from_date, to_date, pct)
    SELECT sheet_id, grade, from_date, to_date, pct FROM rate ORDER BY id;

-- Each recording of a dated setting, oldest first: a value recorded for a key and from-date replaces
-- the one before it.
CREATE TABLE setting_change (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(100) NOT NULL,
    from_date     DATE         NOT NULL,
    setting_value VARCHAR(200) NOT NULL,
    made_on       DATE
);
CREATE INDEX setting_change_sheet ON setting_change (sheet_id, id);
INSERT INTO setting_change (sheet_id, setting_key, from_date, setting_value)
    SELECT sheet_id, setting_key, from_date, setting_value FROM sheet_setting_dated;

-- When a person was added, given each grade, put on each hold and retired.
ALTER TABLE person ADD COLUMN added_on DATE;
ALTER TABLE person ADD COLUMN retired_made_on DATE;
ALTER TABLE person_grade ADD COLUMN made_on DATE;
ALTER TABLE person_hold ADD COLUMN made_on DATE;
