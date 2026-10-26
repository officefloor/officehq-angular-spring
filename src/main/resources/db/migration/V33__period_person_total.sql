-- Each person's due-total for a closed period, fixed when it was closed: one row for every person on the sheet then.
-- A person added later has no row and shows 0.00 for it. Reopening the period removes its rows.
CREATE TABLE period_person_total (
    period_id BIGINT NOT NULL REFERENCES period (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    total DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
