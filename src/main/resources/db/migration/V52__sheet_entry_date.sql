-- A sheet's entry date: while it is a date, a new claim given no date is dated it instead of the sheet's today.
-- None means off.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
