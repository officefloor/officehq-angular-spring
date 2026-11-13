-- Each person's opening balance for a period, added to their carried figure for it: the sum of the
-- amounts pasted for them. A person with no row here has no opening balance for the period.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    opening   DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
