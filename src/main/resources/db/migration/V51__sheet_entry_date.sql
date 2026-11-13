-- A sheet's entry date: while it is set, a new claim given no date (on the claim form, the quick-add
-- bar, a pasted line or a correction) is dated it rather than the sheet's today. Null (blank) is off.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
