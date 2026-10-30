-- Each person's due-total for a closed period, fixed when it was closed, for every person on the
-- sheet then; a person added later has none (shown as 0.00). Removed when the period is reopened.
CREATE TABLE period_person_total (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    total     DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
