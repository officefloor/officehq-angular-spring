-- The sheet's grace days: when its today changes, every open period whose to-date plus them is
-- before the new today is closed; null when blank (off).
ALTER TABLE sheet ADD COLUMN grace_days INTEGER;
