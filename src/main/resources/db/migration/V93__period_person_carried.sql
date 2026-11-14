-- Closing or soft-closing a period fixes, besides each person's due-total for it, each person's
-- carried figure for it at that moment: one row for every person on the sheet then (two decimals).
-- A person added later has no row and counts 0.00. Reopening removes the rows: the figures are live
-- again. The period's kept states carry them as person-id=total pairs too (blank while it is open).
CREATE TABLE period_person_carried (
    period_id BIGINT NOT NULL REFERENCES period (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    carried DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);

ALTER TABLE period_history ADD COLUMN person_carried VARCHAR(100000) NOT NULL DEFAULT '';
