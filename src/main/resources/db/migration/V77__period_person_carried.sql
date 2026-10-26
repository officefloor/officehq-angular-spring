-- Closing or soft-closing a period also fixes, at that moment, each person's carried figure for it:
-- the dues of their counting claims belonging to a period ending before it starts that were still
-- open at its end. A person with no row (added after the close) has 0.00. Reopening removes the
-- rows: the carried figures are live again.
CREATE TABLE period_person_carried (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    carried   DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
