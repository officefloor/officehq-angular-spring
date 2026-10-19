-- A rate can carry a to-date: it applies through that date, and not after it.
ALTER TABLE rate ADD COLUMN to_date DATE;
ALTER TABLE rate ADD CONSTRAINT rate_to_not_before_from CHECK (to_date IS NULL OR from_date IS NULL OR to_date >= from_date);
