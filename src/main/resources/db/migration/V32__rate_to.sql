-- A rate may carry a to-date: it applies through that date, and after it the grade has no rate
-- until a later from-date of the grade takes over. A rate with no to-date has no end.
ALTER TABLE rate ADD COLUMN to_date DATE;
