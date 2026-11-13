-- A sheet's grace days: when the sheet's today is changed, every open period whose to-date plus them
-- is before the new today is closed. Null (blank) is off.
ALTER TABLE sheet ADD COLUMN grace_days INTEGER;
