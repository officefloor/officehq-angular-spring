-- Closing a period also fixes, at that moment, each person's due-total for it: the figures of their
-- counting claims dated in it. A person with no row (added after the close) has 0.00 for it.
-- Reopening removes the rows: the figures are live again.
CREATE TABLE period_person_total (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    total     DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
