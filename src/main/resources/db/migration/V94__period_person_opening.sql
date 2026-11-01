-- Each person's opening balance for a period: pasted amounts added up, counted into their carried figure
-- for it while it is open, and into the figure fixed when it is closed or soft-closed.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    amount    DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
