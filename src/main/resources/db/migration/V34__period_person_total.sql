-- Each person's due-total for a period, fixed when the period was closed: the dues of their OPEN and SETTLED claims
-- dated in it, skipping those with no rate. One row per person on the sheet at that moment; none while the period is
-- open. The period's closed_total is the sum of these.
CREATE TABLE period_person_total (
    period_id BIGINT        NOT NULL REFERENCES period (id),
    person_id BIGINT        NOT NULL,
    total     DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
