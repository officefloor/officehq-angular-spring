-- The sheet's entry date (not dated): while set, a new claim given no date takes it rather than the
-- sheet's today. Null means off.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
