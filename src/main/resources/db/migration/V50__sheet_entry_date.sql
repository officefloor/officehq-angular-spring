-- The sheet's entry date: while it is set, it is the default date of a new claim in place of the sheet's today.
-- Not dated; null (blank) means off.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
