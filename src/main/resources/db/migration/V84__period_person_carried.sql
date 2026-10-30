-- Each person's carried figure for a closed or soft-closed period, fixed when it was closed, for every person
-- on the sheet then; a person added later has none (shown as 0.00). Removed when the period is reopened.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    carried   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
