-- A rate may carry a to-date: it applies through it, and a claim dated after it has no rate unless a
-- later rate of the grade is in force on its date. A rate with no to-date applies until the next
-- rate's from-date. A to-date is never before the rate's from-date.
ALTER TABLE rate ADD COLUMN to_date DATE;
ALTER TABLE rate ADD CONSTRAINT rate_to_after_from CHECK (to_date IS NULL OR from_date IS NULL OR to_date >= from_date);
