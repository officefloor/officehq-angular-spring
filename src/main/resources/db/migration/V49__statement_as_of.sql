-- A statement can be taken as of a date on or before the sheet's today: it binds the person's claims
-- that counted at the end of that date to their figures as they stood then, counting every change
-- made while the sheet's today was on or before it and no later one. So each change a figure hangs on
-- records the sheet's today it was made on (empty: made before this was kept, which always counts).

-- The sheet's today when the statement was taken; statement_date is the date it is as of (the same
-- for a statement taken now).
ALTER TABLE statement ADD COLUMN taken_on DATE;
UPDATE statement SET taken_on = statement_date;

-- The sheet's today when the person was added; no statement is taken as of a date before it.
ALTER TABLE person ADD COLUMN added_on DATE;

-- The sheet's today when their retirement date was last set.
ALTER TABLE person ADD COLUMN retired_on DATE;

-- The sheet's today when each hold was put on.
ALTER TABLE person_hold ADD COLUMN set_on DATE;

-- Each grade recorded for a person after the first, oldest first, with the sheet's today it was
-- recorded on; a later line for the same from-date replaces an earlier one from when it was recorded.
CREATE TABLE person_grade_log (
    person_id BIGINT  NOT NULL REFERENCES person (id),
    seq       INT     NOT NULL,
    from_date DATE    NOT NULL,
    grade     CHAR(1) NOT NULL,
    set_on    DATE,
    PRIMARY KEY (person_id, seq)
);
INSERT INTO person_grade_log (person_id, seq, from_date, grade, set_on)
    SELECT person_id, ROW_NUMBER() OVER (PARTITION BY person_id ORDER BY from_date) - 1, from_date, grade, NULL
    FROM person_grade;

-- Each rate set, oldest first, with the sheet's today it was set on; a later line for the same grade
-- and from-date replaces an earlier one from when it was set.
CREATE TABLE rate_log (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    grade     CHAR(1)       NOT NULL,
    from_date DATE,
    to_date   DATE,
    pct       DECIMAL(7, 2) NOT NULL,
    set_on    DATE
);
CREATE INDEX rate_log_sheet ON rate_log (sheet_id, id);
INSERT INTO rate_log (sheet_id, grade, from_date, to_date, pct, set_on)
    SELECT sheet_id, grade, from_date, to_date, pct, NULL FROM rate ORDER BY id;

-- Each dated setting value recorded, oldest first, with the sheet's today it was recorded on; a later
-- line for the same key and from-date replaces an earlier one from when it was recorded.
CREATE TABLE sheet_setting_log (
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    seq           INT          NOT NULL,
    setting_key   VARCHAR(40)  NOT NULL,
    from_date     DATE         NOT NULL,
    setting_value VARCHAR(40),
    set_on        DATE,
    PRIMARY KEY (sheet_id, seq)
);
INSERT INTO sheet_setting_log (sheet_id, seq, setting_key, from_date, setting_value, set_on)
    SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY setting_key, from_date) - 1,
           setting_key, from_date, setting_value, NULL
    FROM sheet_setting;

-- The sheet's today when the claim was deferred.
ALTER TABLE claim ADD COLUMN deferred_on DATE;

-- Each binding of a claim by a statement and each release of it, oldest first, with the sheet's today
-- then: stated, with the figure bound (empty when none), or not. A claim with no lines is bound as
-- its stated column says.
CREATE TABLE claim_binding (
    claim_id BIGINT  NOT NULL REFERENCES claim (id),
    seq      INT     NOT NULL,
    on_date  DATE    NOT NULL,
    stated   BOOLEAN NOT NULL,
    due      DECIMAL(14, 2),
    PRIMARY KEY (claim_id, seq)
);
