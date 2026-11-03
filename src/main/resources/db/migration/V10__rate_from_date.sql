-- A rate can carry a from-date: a grade may have several rates, each in force from its from-date
-- (none: from the beginning of time) until the next one. One rate per grade and from-date.
ALTER TABLE rate ADD COLUMN from_date DATE;
ALTER TABLE rate ADD COLUMN from_key DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'));
DROP INDEX rate_sheet_grade;
CREATE UNIQUE INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_key);
