-- The sheet's entry date: while set, the date a new claim takes when none is given, in place of the
-- sheet's today; null when blank (off). Not dated.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
