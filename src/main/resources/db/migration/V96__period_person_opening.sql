-- Each person's opening balance for a period: the sum of the amounts pasted for them into it, added to their carried
-- figure for it. Kept whether the period is open or closed; a closed period's fixed carried figure stays as fixed.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    opening   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
