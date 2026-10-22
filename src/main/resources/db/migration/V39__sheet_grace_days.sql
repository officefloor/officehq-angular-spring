-- Grace days: when the sheet's today is changed, every open period whose to-date plus them is before
-- the new today closes itself; null for off.
ALTER TABLE sheet ADD COLUMN grace_days INT;
