-- Each person's opening balance for a period: the sum of the amounts pasted for them into it, added to their carried
-- figure for it. A person with no line has 0.00.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    opening   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
