-- A rate may carry a from-date: it applies to dates on or after it. A rate with no from-date applies
-- from the beginning of time. A grade has at most one rate per from-date (and one with none).
ALTER TABLE rate ADD COLUMN from_date DATE;
ALTER TABLE rate ADD COLUMN from_key DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'));
ALTER TABLE rate DROP CONSTRAINT rate_grade_unique;
ALTER TABLE rate ADD CONSTRAINT rate_grade_from_unique UNIQUE (sheet_id, grade, from_key);
