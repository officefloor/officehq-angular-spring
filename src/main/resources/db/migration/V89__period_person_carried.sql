-- Each person's carried figure into a closed (or soft-closed) period, fixed when it was closed: what
-- is due on their counting claims due by a date in an earlier period that were still OPEN or PENDING
-- at the end of its last day. A person with no row here shows 0.00 carried into the closed period.
-- The rows are removed when the period is reopened.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    carried   DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
