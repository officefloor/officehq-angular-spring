-- A closed period fixes each person's due-total for it too, at the figure it had when it was
-- closed, for every person on the sheet then (a person added later has none: 0.00). The rows go
-- when the period is reopened.
CREATE TABLE period_person_total (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    due_total DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
