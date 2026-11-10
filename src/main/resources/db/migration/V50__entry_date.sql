-- The sheet's entry date (none: off): while set, the default date of a new claim in place of the
-- sheet's today.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
