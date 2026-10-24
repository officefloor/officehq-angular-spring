-- Each person's due-total for a closed period, fixed when it was closed: what is due on their
-- counting claims dated in it. A person with no row here shows 0.00 for the closed period. The
-- rows are removed when the period is reopened.
CREATE TABLE period_person_total (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    total     DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
