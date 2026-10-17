-- A rate can carry a from-date: a grade may have several rates, one per from-date, and a rate with
-- no from-date applies from the beginning of time. A grade has at most one rate per from-date
-- (including at most one without a from-date, which the service ensures).
ALTER TABLE rate DROP CONSTRAINT rate_sheet_grade;
ALTER TABLE rate ADD COLUMN from_date DATE;
ALTER TABLE rate ADD CONSTRAINT rate_sheet_grade_from UNIQUE (sheet_id, grade, from_date);
