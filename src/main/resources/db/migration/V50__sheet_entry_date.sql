-- The sheet's entry date: while it is set, a new claim with no date is dated on it rather than the sheet's today.
-- Null (blank) means off.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
