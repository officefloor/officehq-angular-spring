-- A rate may end: it applies through its to-date; null when it has no end.
ALTER TABLE rate ADD COLUMN to_date DATE;
