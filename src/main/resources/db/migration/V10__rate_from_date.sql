-- A rate applies from its from-date; a rate with no from-date applies from the beginning of time.
-- A grade may have several rates, one per from-date (the key folds "no from-date" into one value).
ALTER TABLE rate ADD COLUMN from_date DATE;
ALTER TABLE rate ADD COLUMN from_key DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'));
DROP INDEX rate_sheet_grade;
CREATE UNIQUE INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_key);
