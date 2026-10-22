-- A rate may carry a from-date: it applies to claims dated on or after it, until the next rate's
-- from-date for the same grade. A rate with no from-date applies from the beginning of time. A
-- sheet holds at most one rate per grade and from-date (one with none per grade, kept by the app).
ALTER TABLE rate ADD COLUMN from_date DATE;
DROP INDEX rate_sheet_grade;
CREATE UNIQUE INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_date);
