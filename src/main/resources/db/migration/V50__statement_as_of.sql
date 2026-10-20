-- A statement may be taken as of a date on or before the sheet's today: it goes by the sheet as it stood at the
-- end of that date, every change made while the sheet's today was on or after the next day left out. Each change
-- that moves a figure is therefore kept with the sheet's today when it was made (its made-on date); one already
-- there has none and counts as of every date.
ALTER TABLE statement ADD COLUMN as_of DATE;
ALTER TABLE person ADD COLUMN added_on DATE;
ALTER TABLE person ADD COLUMN retired_on DATE;
ALTER TABLE person_hold ADD COLUMN made_on DATE;
ALTER TABLE person_grade ADD COLUMN made_on DATE;
ALTER TABLE sheet_setting_from ADD COLUMN made_on DATE;
-- Every rate set, in the order set, with the sheet's today then; the rate table keeps only the latest of each.
CREATE TABLE rate_log (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade     VARCHAR(1)   NOT NULL,
    from_date DATE,
    to_date   DATE,
    rate      DECIMAL(5, 2) NOT NULL,
    made_on   DATE         NOT NULL
);
CREATE INDEX rate_log_sheet ON rate_log (sheet_id, id);
-- A claim's binding log: one line each time a statement binds it to a figure (none when n/a) or leaves it
-- unbound, oldest first, with the sheet's today then. A claim already there has no log.
CREATE TABLE claim_binding_log (
    claim_id   BIGINT        NOT NULL REFERENCES claim (id),
    seq        INT           NOT NULL,
    on_date    DATE          NOT NULL,
    stated     BOOLEAN       NOT NULL,
    stated_due DECIMAL(12, 2),
    PRIMARY KEY (claim_id, seq)
);
