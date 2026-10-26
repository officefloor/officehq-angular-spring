-- Each person's opening balance for a period, pasted as lines of name,period,amount: each line adds its
-- amount to the person's row. It counts toward their carried figure for the period, live while the
-- period is open and fixed with the rest of the figure when it is closed or soft-closed. A person
-- with no row has 0.00.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    amount    DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
