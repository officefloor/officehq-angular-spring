-- Each person's carried figure for a closed or soft-closed period, fixed at the moment it was closed, as their
-- due-total for it is. A person with no line (added later) has 0.00 for it. Removed when the period is reopened.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    carried   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
