-- A rate can carry a from-date: a grade may have several rates, and a claim uses the one whose
-- from-date is the latest on or before its date. A rate with no from-date applies from the
-- beginning of time. A grade has at most one rate per from-date (and one with none).
ALTER TABLE rate ADD COLUMN from_date DATE;
ALTER TABLE rate ADD COLUMN from_key DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'));
DROP INDEX rate_sheet_grade;
CREATE UNIQUE INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_key);
