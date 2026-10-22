-- Closing a period also fixes each person's due-total for it at that moment, one row per person then
-- on the sheet; a person added later has no row and shows 0.00 for it. Reopening removes the rows.
-- The period's closed_total is the sum of these.
CREATE TABLE period_person_total (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    total     DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
