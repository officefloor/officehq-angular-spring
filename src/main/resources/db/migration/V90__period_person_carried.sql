-- Each person's carried figure for a period, fixed when the period was closed or soft-closed: the dues of their
-- claims due by a date in an earlier period that were OPEN or PENDING at the end of its last day. One row per person
-- on the sheet at that moment; none while the period is open.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    carried   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
