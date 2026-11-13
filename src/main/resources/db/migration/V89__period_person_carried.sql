-- Closing or soft-closing a period fixes each person's carried figure for it too, as it was then;
-- reopening clears them. A person with no row here shows 0.00 carried for a closed period.
CREATE TABLE period_person_carried (
    period_id BIGINT        NOT NULL REFERENCES period (id),
    person_id BIGINT        NOT NULL REFERENCES person (id),
    carried   DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
