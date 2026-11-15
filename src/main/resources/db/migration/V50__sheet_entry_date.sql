-- The sheet's entry date: while set, a new claim left undated takes it instead of the sheet's
-- today; null when blank (off).
ALTER TABLE sheet ADD COLUMN entry_date DATE;
