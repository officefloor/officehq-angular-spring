-- Each person's due-total for a closed period, fixed when it was closed (the due of their claims
-- that count whose date falls in it), one row for every person on the sheet at that moment. A
-- person added later has none (shown as 0.00). Reopening removes them: the figures are live again.
CREATE TABLE period_person_due (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    due       DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
