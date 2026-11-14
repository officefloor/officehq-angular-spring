-- A rate may carry a to-date: it applies through it. A claim dated after the to-date of the rate
-- that would apply to it has no rate (n/a). Rates kept before this change have none.
ALTER TABLE rate ADD COLUMN to_date DATE;
ALTER TABLE rate ADD CONSTRAINT rate_to_after_from CHECK (to_date IS NULL OR from_date IS NULL OR to_date >= from_date);
