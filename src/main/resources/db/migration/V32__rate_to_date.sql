-- A rate can carry a to-date: it applies through that day and not after. A rate with none does not end;
-- a to-date is never before the rate's from-date.
ALTER TABLE rate ADD COLUMN to_date DATE;
ALTER TABLE rate ADD CONSTRAINT rate_to_after_from CHECK (to_date IS NULL OR from_date IS NULL OR to_date >= from_date);
