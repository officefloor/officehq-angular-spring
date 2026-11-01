-- Closing or soft-closing a period also fixes each person's carried figure for it at that moment, for
-- every person on the sheet: the dues of their claims that count due in an earlier period and still
-- open at the end of its last day. A person added later has no line and shows 0.00. Reopening the
-- period removes its lines: the figures are live again.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    carried   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
