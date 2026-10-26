-- A rate may also carry a to-date: it applies through that date (null: with no end).
ALTER TABLE rate ADD COLUMN to_date DATE;
