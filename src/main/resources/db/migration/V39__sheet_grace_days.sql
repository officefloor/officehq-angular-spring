-- The sheet's grace days: when the sheet's today is changed, an open period whose to-date plus these
-- days is before the new today is closed; null (blank) means off. Not dated.
ALTER TABLE sheet ADD COLUMN grace_days INT;
