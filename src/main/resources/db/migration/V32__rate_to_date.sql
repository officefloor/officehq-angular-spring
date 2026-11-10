-- A rate can carry a to-date: it is in force through it (none: until the grade's next rate).
ALTER TABLE rate ADD COLUMN to_date DATE;
ALTER TABLE rate ADD CONSTRAINT rate_to_after_from CHECK (to_date IS NULL OR from_date IS NULL OR to_date >= from_date);
