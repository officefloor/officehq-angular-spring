-- The sheet's entry date: while it is set, a claim added or edited without a date is dated it rather
-- than the sheet's today; null when it is off.
ALTER TABLE sheet ADD COLUMN entry_date DATE;
