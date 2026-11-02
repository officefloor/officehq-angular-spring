-- The sheet's entry date: while it is set, the default date of a new claim in place of the sheet's
-- today; null (blank) is off.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
