-- Each person's opening balance for a period: what pasted opening balances have added to their
-- carried figure for it. A person with no row has 0.00 opening. Unlike the fixed carried figures it
-- stays when the period is reopened.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    opening   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
-- The sheet's history keeps them too, as text: "person-id:opening", separated by ";".
ALTER TABLE period_revision ADD COLUMN person_openings VARCHAR(4000) DEFAULT '' NOT NULL;
