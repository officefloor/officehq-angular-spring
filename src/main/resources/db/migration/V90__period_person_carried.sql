-- Closing or soft-closing a period also fixes each person's carried figure for it at that moment, one
-- row per person then on the sheet; a person added later has no row and shows 0.00. Reopening removes
-- the rows, and the carried figures are live again.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    carried   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
