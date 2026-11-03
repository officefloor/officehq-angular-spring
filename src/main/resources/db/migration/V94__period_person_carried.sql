-- Each person's carried figure for a closed or soft-closed period, fixed when it was closed: the due
-- of their claims that count whose due-by falls in an earlier period and that were still open (or
-- pending) at its end. A person with no row has 0.00 carried into it. The rows go when the period is
-- reopened. A period closed before this was kept has none fixed: its carried figures show 0.00.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    carried   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
-- The sheet's history keeps them too, as text: "person-id:carried", separated by ";".
ALTER TABLE period_revision ADD COLUMN person_carried VARCHAR(4000) DEFAULT '' NOT NULL;
