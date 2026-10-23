-- Each person's opening balance for a period, the sum of the amounts imported for them in it: it adds
-- to their carried figure for the period, live while it is open and fixed with it on a close.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    amount    DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
