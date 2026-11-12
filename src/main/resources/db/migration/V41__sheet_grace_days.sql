-- The sheet's grace days: when the sheet's today changes, every open period whose to-date plus the grace days
-- is before the new today is closed. None means off. Not dated.
ALTER TABLE sheet ADD COLUMN grace_days INTEGER CHECK (grace_days >= 0);
