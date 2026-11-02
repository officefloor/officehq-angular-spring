-- A rate may carry a from-date: it applies from that date on, until a later from-date of the same
-- grade takes over. A rate with no from-date applies from the beginning of time. A grade has at
-- most one rate per from-date (and at most one with none; the service keeps that).
ALTER TABLE rate ADD COLUMN from_date DATE;
ALTER TABLE rate DROP CONSTRAINT rate_sheet_grade_unique;
ALTER TABLE rate ADD CONSTRAINT rate_sheet_grade_from_unique UNIQUE (sheet_id, grade, from_date);
