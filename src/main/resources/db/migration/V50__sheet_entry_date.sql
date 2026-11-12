-- The sheet's entry date: while set, the default date of a new claim in place of the sheet's today. None
-- (blank) means off. Not dated.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
