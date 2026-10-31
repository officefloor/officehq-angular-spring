-- Each person's due-total for a closed period, fixed at the moment it was closed; the period's closed_total is
-- their sum. A person with no line (added later) has 0.00 for it. Removed when the period is reopened.
CREATE TABLE period_person_total (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    due_total DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
