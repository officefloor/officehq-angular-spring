-- The sheet's entry date: while set, the date a new claim takes when none is given, in place of the sheet's
-- today. None means off. Not dated.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
