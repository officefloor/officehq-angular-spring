-- Each person's due-total for a closed period, fixed when it was closed: the due of their claims
-- that count whose date falls in it. A person with no row (added after the close) has 0.00 for
-- it. The rows go when the period is reopened.
CREATE TABLE period_person_total (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    total     DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
