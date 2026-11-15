-- A closed or soft-closed period fixes each person's carried figure for it too, at the figure it
-- had when it was closed, for every person on the sheet then (a person added later has none: 0.00).
-- The rows go when the period is reopened. A period closed before carried figures were fixed has
-- none fixed (carried_fixed false) and keeps showing them live.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    carried   DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);

ALTER TABLE period ADD COLUMN carried_fixed BOOLEAN NOT NULL DEFAULT FALSE;
