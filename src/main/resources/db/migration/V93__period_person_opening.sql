-- Each person's opening balance for a period: the sum of the amounts pasted for them into it, added to their carried
-- figure for it. A person with no row has 0.00.
CREATE TABLE period_person_opening (
    period_id BIGINT NOT NULL REFERENCES period (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    amount DECIMAL(14, 2) NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (period_id, person_id)
);
