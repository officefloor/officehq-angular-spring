-- Closing a period fixes each person's due-total for it too, as it was then; reopening clears them.
-- A person with no row here shows 0.00 for a closed period.
CREATE TABLE period_person_total (
    period_id BIGINT        NOT NULL REFERENCES period (id),
    person_id BIGINT        NOT NULL REFERENCES person (id),
    total     DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
