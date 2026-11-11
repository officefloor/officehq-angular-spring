-- A rate may carry a from-date: it applies from that date on, until a later-dated rate for the same
-- grade takes over. A rate with no from-date applies from the beginning of time. A grade has at
-- most one rate per from-date (and at most one with none); from_key stands in for the missing
-- from-date so the unique index covers that one too.
ALTER TABLE rate ADD COLUMN from_date DATE;
ALTER TABLE rate ADD COLUMN from_key DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01'));
DROP INDEX rate_sheet_grade;
CREATE UNIQUE INDEX rate_sheet_grade_from ON rate (sheet_id, grade, from_key);
