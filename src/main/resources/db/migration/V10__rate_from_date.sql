-- A rate may carry a from-date: it applies to claims dated on or after it, until a later from-date
-- of the same grade takes over. A rate with no from-date applies from the beginning of time. A grade
-- has at most one rate per from-date (and at most one without one); setting it again replaces it.
ALTER TABLE rate ADD COLUMN from_date DATE;
DROP INDEX rate_sheet_grade;
CREATE UNIQUE INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_date);
