-- Each person's opening balance for a period, pasted in: added to their carried figure into it, live
-- while it is open and fixed with the rest of the carried figure when it is closed. A person with no
-- row here has no opening balance for the period.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    opening   DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
