-- A rate may also have a to-date: it applies through it (included). A rate with none applies on with
-- no end.
ALTER TABLE rate ADD COLUMN to_date DATE;
