-- A rate may carry a from-date: a grade may have several rates, one per from-date, and a claim
-- uses the one with the latest from-date on or before its date. A rate with no from-date applies
-- from the beginning of time. Rates kept before this change have none.
DROP INDEX rate_sheet_grade;
ALTER TABLE rate ADD COLUMN from_date DATE;
-- One rate per grade and from-date, an undated rate counting as one from-date of its own.
ALTER TABLE rate ADD COLUMN from_key DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'));
CREATE UNIQUE INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_key);
