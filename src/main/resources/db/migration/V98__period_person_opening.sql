-- Each person's opening balance for a period, pasted as name,period,amount lines: every line adds its
-- amount (two decimals, 0.00 or more) to the person's carried figure for the period. Closing or
-- soft-closing the period fixes the carried figures, openings included; an opening pasted while it is
-- closed is kept and counts once it is reopened.
CREATE TABLE period_person_opening (
    period_id BIGINT NOT NULL REFERENCES period (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    amount DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
