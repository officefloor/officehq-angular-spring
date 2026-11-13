-- Closing a period fixes, besides its own due-total, each person's due-total for it at that moment:
-- one row for every person on the sheet then (two decimals). A person added later has no row and
-- counts 0.00 for the period. Reopening removes the rows: the figures are live again.
CREATE TABLE period_person_total (
    period_id BIGINT NOT NULL REFERENCES period (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    total DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
