-- A rate applies from its from-date on; a rate with no from-date applies from the beginning of
-- time. A grade may have several rates, one per from-date (one with none).
ALTER TABLE rate ADD COLUMN from_date DATE;
DROP INDEX rate_sheet_grade;
CREATE UNIQUE NULLS NOT DISTINCT INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_date);
