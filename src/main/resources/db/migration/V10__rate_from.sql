-- A rate may carry a from-date: a grade may have several rates, and a claim takes the one whose
-- from-date is the latest on or before its date. A rate with no from-date applies from the
-- beginning of time. One rate per grade and from-date (the service keeps the no-from-date one
-- unique, since NULLs are distinct in a unique index).
ALTER TABLE rate ADD COLUMN from_date DATE;
DROP INDEX rate_sheet_grade;
CREATE UNIQUE INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_date);
