-- Each person's opening balance for a period, pasted in: added to their carried figure for it. Kept while the
-- period is closed and reopened; a closed period shows the carried figure fixed then.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    opening   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
