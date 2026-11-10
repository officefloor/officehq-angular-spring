-- Each person's carried figure for a closed or soft-closed period, fixed when it was closed (the
-- due of their claims that count belonging to an earlier period and still OPEN or PENDING at the
-- end of its to-date), one row for every person on the sheet at that moment. A person added later
-- has none (shown as 0.00). Reopening removes them: the figures are live again.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    carried   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
