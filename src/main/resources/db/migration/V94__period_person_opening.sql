-- Each person's opening balance for a period, pasted as name,period,amount: added to their carried
-- figure into it. Each paste adds its amount to what is there.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    opening   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
