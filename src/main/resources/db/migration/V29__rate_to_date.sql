-- A rate can carry a to-date: it is in force through that date and no longer. A claim dated after
-- the to-date of the rate that would apply to it (the latest from-date on or before the claim's
-- date) has no rate. A to-date is never before the rate's from-date.
ALTER TABLE rate ADD COLUMN to_date DATE;
ALTER TABLE rate ADD CONSTRAINT rate_to_not_before_from CHECK (to_date IS NULL OR from_date IS NULL OR to_date >= from_date);
